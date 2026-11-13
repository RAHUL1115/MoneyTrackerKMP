package app.moneytracker.ai

import kotlin.math.exp

/**
 * On-device Smart entry: turns what someone says or types ("1,200 for new shoes on my credit card", also Hinglish and Hindi) into the pieces of an entry.
 * Rules keep money, dates and accounts exact; small trained models find the pieces, the type and the category.
 * Nothing leaves the phone. Model files are produced by the training scripts and must match this code.
 */
class SmartParser private constructor(
    private val meta: Map<String, Any?>,
    private val tagger: QTable,
    private val typer: QTable,
    private val encoder: QTable,
    private val encoderHead: QTable,
    private val knowledge: QTable,
    private val knowledgeHead: QTable,
    private val splitter: PieceSplitter,
) {
    data class Category(val name: String, val score: Float)

    data class Result(
        val type: String,               // expense | income | transfer | refund
        val typeConfidence: Float,
        val amount: Double?,            // in main units, e.g. 50.0 = ₹50
        val items: List<String>,        // what was bought, as heard
        val account: String,            // one of the given account names, or ""
        val daysAgo: Int,               // 0 today, 1 yesterday, 2 day before
        val categories: List<Category>, // best first; empty for transfers
        val words: List<Pair<String, String>>, // each word with its label (AMT, ITEM, ACC, DATE, QTY, O)
        /** [items] in the words as heard (not the model's simplified spelling); Hindi script written in Latin letters. For notes. */
        val itemsHeard: List<String> = items,
    )

    companion object {
        /** Files under the model folder, in the order [load] reads them. */
        val FILES = listOf("meta.json", "tagger.bin", "typer.bin", "encoder.bin", "encoder_head.bin", "knowledge.bin", "knowledge_head.bin", "pieces.txt")

        fun load(read: (String) -> ByteArray): SmartParser {
            @Suppress("UNCHECKED_CAST")
            val meta = MiniJson.parse(read("meta.json").decodeToString()) as Map<String, Any?>
            val pieces = read("pieces.txt").decodeToString().split('\n').filter { it.isNotEmpty() }.map { line ->
                val tab = line.indexOf('\t'); line.substring(tab + 1) to line.substring(0, tab).toFloat()
            }
            return SmartParser(meta, QTable.read(read("tagger.bin")), QTable.read(read("typer.bin")), QTable.read(read("encoder.bin")),
                QTable.read(read("encoder_head.bin")), QTable.read(read("knowledge.bin")), QTable.read(read("knowledge_head.bin")), PieceSplitter(pieces))
        }
    }

    // ───────────────────────── metadata
    @Suppress("UNCHECKED_CAST") private fun strList(k: String) = meta[k] as List<String>
    @Suppress("UNCHECKED_CAST") private fun numMap(k: String) = (meta[k] as Map<String, Double>)
    private fun int(k: String) = (meta[k] as Double).toInt()

    private val tags = strList("tags")
    private val types = strList("types")
    private val en = numMap("en"); private val hi = numMap("hi"); private val scaleWords = numMap("scale"); private val half = numMap("half")
    private val currency = strList("currency").toSet()
    private val correction = strList("correction").toSet()
    private val genericAcc = strList("genericAcc").toSet()
    private val lead = strList("lead").toSet()
    private val cashWords = strList("cashWords").toSet()
    @Suppress("UNCHECKED_CAST") private val aliases = meta["aliases"] as Map<String, String>
    private val taggerBuckets = int("taggerBuckets"); private val typerBuckets = int("typerBuckets"); private val encoderBuckets = int("encoderBuckets")
    @Suppress("UNCHECKED_CAST") private val mix = meta["mix"] as Map<String, Double>
    @Suppress("UNCHECKED_CAST") private val concepts = (meta["concepts"] as List<Map<String, Any?>>).map { (it["names"] as List<String>) }

    internal fun toks(text: String) = SmartText.toks(text, aliases)

    // ───────────────────────── numbers and dates (rules)
    private val numRe = Regex("(\\d+(?:\\.\\d+)?)(k?)")
    private val digitsRe = Regex("\\d+")
    private val digitsXRe = Regex("[\\d.]+k?")

    /** ["two","fifty"] → 250, ["1.5k"] → 1500, ["dedh","sau"] → 150, ["sadhe","tin","sau"] → 350. */
    internal fun parseAmount(words: List<String>): Double? {
        val vals = ArrayList<Double>(); var cur: Double? = null; var h = 0.0
        for (w in words) {
            if (w in currency) continue
            if (w in half) { h = half.getValue(w); continue }
            val m = numRe.matchEntire(w)
            val v: Double = when {
                m != null -> m.groupValues[1].toDouble() * (if (m.groupValues[2].isNotEmpty()) 1000 else 1)
                w in en && w != "a" -> en.getValue(w)
                w in hi -> hi.getValue(w)
                w in scaleWords -> { cur = ((cur ?: 1.0) + h) * scaleWords.getValue(w); h = 0.0; continue }
                else -> continue
            }
            val c = cur
            cur = when {
                c != null && c >= 100 && v < c -> c + v
                c != null && c < 10 && v >= 10 && v < 100 -> c * 100 + v
                c != null && c >= 20 && c % 10 == 0.0 && v < 10 -> c + v
                else -> { if (c != null) vals.add(c); v }
            }
        }
        cur?.let { vals.add(if (h != 0.0 && it < 100) it + h else it) }
        return vals.firstOrNull()
    }

    private val parsoRe = Regex("\\bparso")
    private val kalRe = Regex("\\bkal\\b")

    internal fun daysAgo(words: List<String>): Int {
        val s = words.joinToString(" ")
        if ("day before yesterday" in s || parsoRe.containsMatchIn(s)) return 2
        if ("yesterday" in s || "yestarday" in s || kalRe.containsMatchIn(s) || "last night" in s) return 1
        return 0
    }

    // ───────────────────────── model 1: word labels
    private fun shape(w: String) = when { digitsRe.matches(w) -> "d"; digitsXRe.matches(w) -> "dx"; else -> "a" }
    private fun py(b: Boolean) = if (b) "True" else "False"
    private val numTokRe = Regex("\\d+(\\.\\d+)?k?")
    private fun isNumTok(w: String) = numTokRe.matches(w) || w in en || w in hi || w in scaleWords || w in half

    private fun tagFeats(ws: List<String>, i: Int, prev: String): List<String> {
        val w = ws[i]
        fun g(j: Int) = if (j in ws.indices) ws[j] else if (j < 0) "<s>" else "</s>"
        return listOf("b", "w=$w", "p3=" + w.take(3), "s3=" + w.takeLast(3), "sh=" + shape(w), "num=" + py(isNumTok(w)), "cur=" + py(w in currency),
            "w-1=" + g(i - 1), "w+1=" + g(i + 1), "w-2=" + g(i - 2), "w+2=" + g(i + 2), "sh-1=" + shape(g(i - 1)), "sh+1=" + shape(g(i + 1)),
            "num-1=" + py(isNumTok(g(i - 1))), "num+1=" + py(isNumTok(g(i + 1))), "t-1=$prev", "t-1w=$prev|$w", "w-1w=" + g(i - 1) + "|" + w)
    }

    internal fun label(ws: List<String>): List<String> {
        val out = ArrayList<String>(ws.size); var prev = "<s>"
        for (i in ws.indices) {
            val acc = FloatArray(tags.size)
            for (f in tagFeats(ws, i, prev)) tagger.addRow(tagger.rowOf((SmartText.fnv1a(f) % taggerBuckets).toInt()), acc)
            val t = tags[argmax(acc)]; out.add(t); prev = t
        }
        return out
    }

    // ───────────────────────── model 2: expense / income / transfer / refund
    internal fun typeProbs(ws: List<String>): FloatArray {
        val feats = ArrayList<String>().apply { add("b"); ws.forEach { add("w=$it") }; for (k in 0 until ws.size - 1) add("bi=" + ws[k] + "|" + ws[k + 1]) }
        val acc = FloatArray(types.size)
        for (f in feats) typer.addRow(typer.rowOf((SmartText.fnv1a(f) % typerBuckets).toInt()), acc)
        return softmax(acc)
    }

    // ───────────────────────── model 3a: our category encoder (letter pieces of words)
    private fun encFeats(text: String): List<String> {
        val ws = toks(text); val f = ArrayList<String>()
        for (w in ws) {
            f.add("w=$w"); val g = "<$w>"
            for (n in 3..5) for (i in 0..g.length - n) f.add("c=" + g.substring(i, i + n))
        }
        for (k in 0 until ws.size - 1) f.add("bi=" + ws[k] + "|" + ws[k + 1])
        return f.ifEmpty { listOf("<empty>") }
    }

    private fun encVec(text: String): FloatArray {
        val feats = encFeats(text); val acc = FloatArray(encoder.cols)
        for (f in feats) encoder.addRow(encoder.rowOf((SmartText.fnv1a(f) % encoderBuckets).toInt()), acc)
        for (c in acc.indices) acc[c] /= feats.size
        return normalize(acc, 1e-8f)
    }

    private fun encConcepts(text: String): FloatArray {
        val v = encVec(text); val z = FloatArray(encoderHead.rows) { r -> var s = 0f; for (c in v.indices) s += encoderHead.get(r, c) * v[c]; s }
        return softmax(z)
    }

    // ───────────────────────── model 3b: borrowed world knowledge (multilingual word pieces)
    private fun kVec(text: String): FloatArray {
        val ids = splitter.split(text); val acc = FloatArray(knowledge.cols)
        if (ids.isEmpty()) return acc
        for (id in ids) knowledge.addRow(id, acc)
        for (c in acc.indices) acc[c] /= ids.size
        return normalize(acc)
    }

    private fun kConcepts(text: String): FloatArray {
        val v = kVec(text); val hs = mix.getValue("headScale").toFloat(); val bias = knowledgeHead.cols - 1
        val z = FloatArray(knowledgeHead.rows) { r -> var s = 0f; for (c in v.indices) s += knowledgeHead.get(r, c) * v[c]; s * hs + knowledgeHead.get(r, bias) }
        return softmax(z)
    }

    // ───────────────────────── concept → this user's categories
    private class Mapping(val cv: List<FloatArray>, val m: Array<FloatArray>)
    private val encMaps = HashMap<List<String>, Mapping>()
    private val kMaps = HashMap<List<String>, Mapping>()

    private fun mapping(categories: List<String>, weighted: Boolean, vec: (String) -> FloatArray, cache: HashMap<List<String>, Mapping>): Mapping =
        cache.getOrPut(categories) {
            val sharp = mix.getValue("sharp").toFloat()
            val cv = categories.map { vec(SmartText.cleanCategory(it)) }
            val m = Array(concepts.size) { k ->
                val names = concepts[k]; val nv = names.map(vec)
                val sim = FloatArray(categories.size) { j ->
                    var best = Float.NEGATIVE_INFINITY
                    for (i in nv.indices) { val s = dot(nv[i], cv[j]) * (if (!weighted || i < 3) 1f else .9f); if (s > best) best = s }
                    best
                }
                val mx = sim.max(); val e = FloatArray(sim.size) { exp((sharp * (sim[it] - mx)).toDouble()).toFloat() }; val sum = e.sum()
                FloatArray(e.size) { e[it] / sum }
            }
            Mapping(cv, m)
        }

    private fun categorise(itemFolded: String, itemRaw: String, categories: List<String>): List<Category> {
        if (categories.isEmpty()) return emptyList()
        val wChar = mix.getValue("wChar").toFloat(); val alpha = mix.getValue("alpha").toFloat(); val temp = mix.getValue("directTemp").toFloat()
        val m1 = mapping(categories, true, ::encVec, encMaps); val m2 = mapping(categories, false, ::kVec, kMaps)
        val cp1 = encConcepts(itemFolded); val cp2 = kConcepts(itemRaw)
        val cp = FloatArray(cp1.size) { wChar * cp1[it] + (1 - wChar) * cp2[it] }
        val routed = FloatArray(categories.size) { j -> var a = 0f; var b = 0f; for (k in cp.indices) { a += cp[k] * m1.m[k][j]; b += cp[k] * m2.m[k][j] }; .5f * a + .5f * b }
        val iv1 = encVec(itemFolded); val iv2 = kVec(itemRaw)
        val d1 = FloatArray(categories.size) { dot(m1.cv[it], iv1) }; val d2 = FloatArray(categories.size) { dot(m2.cv[it], iv2) }
        val x1 = d1.max(); val x2 = d2.max()
        val d = FloatArray(categories.size) { (exp((temp * (d1[it] - x1)).toDouble()) + exp((temp * (d2[it] - x2)).toDouble())).toFloat() }
        val ds = d.sum()
        return categories.indices.map { Category(categories[it], alpha * d[it] / ds + (1 - alpha) * routed[it]) }.sortedByDescending { it.score }
    }

    // ───────────────────────── accounts
    private fun resolveAccount(span: String, accounts: List<String>): String {
        if (span.isEmpty()) return ""
        val sw = span.split(' ').filter { it.isNotEmpty() }.toSet()
        // "cash" means an account called Cash, not one that merely contains the letters ("SBI Cashback").
        if (sw.any { it in cashWords }) return accounts.firstOrNull { a -> toks(a).any { it in cashWords } } ?: ""
        val dist = sw - genericAcc; var best = ""; var bs = 0.0; var ties = 0
        for (a in accounts) {
            val aw = toks(a).toSet(); val ad = aw - genericAcc
            var s = (if (dist.isNotEmpty()) 2.0 * (dist intersect ad).size else 0.0) + ((sw - dist) intersect (aw - ad)).size * .25
            if (dist.isNotEmpty() && (dist intersect ad).isEmpty()) s = 0.0
            if (s > bs) { best = a; bs = s; ties = 1 } else if (s == bs && s > 0) ties++
        }
        // Only generic words ("my credit card"): pick an account only when exactly one fits, never an arbitrary card.
        if (dist.isEmpty() && ties > 1) return ""
        return if (bs > 0) best else ""
    }

    // ───────────────────────── the whole thing
    /**
     * [expenseCategories] / [incomeCategories]: the user's own category names (any language, emoji allowed).
     * [accounts]: the user's account names. Categories come back best first; show the first and offer the next two.
     */
    fun parse(text: String, expenseCategories: List<String>, incomeCategories: List<String>, accounts: List<String>): Result {
        val raw = SmartText.norm(text).split(' ').filter { it.isNotEmpty() }
        val ws = toks(text)
        val lab = fixAccountPhrases(ws, label(ws))
        // Spans of equal labels.
        val spans = HashMap<String, MutableList<List<String>>>()
        var cur = ""; var buf = ArrayList<String>()   // no label is empty, so the first word always starts a span
        for ((w, t) in (ws + "</s>").zip(lab + "O")) {
            if (t != cur && buf.isNotEmpty()) { spans.getOrPut(cur) { ArrayList() }.add(buf); buf = ArrayList() }
            cur = t
            if (t != "O") buf.add(w)
        }
        // Amounts; "200 no sorry 250": a correction word between two amounts drops the earlier one.
        val amtSpans = ArrayList<List<String>>(); val pos = ArrayList<IntArray>(); var i = 0
        while (i < ws.size) {
            if (lab[i] == "AMT") { var j = i; while (j < ws.size && lab[j] == "AMT") j++; amtSpans.add(ws.subList(i, j)); pos.add(intArrayOf(i, j)); i = j } else i++
        }
        val keep = ArrayList<Int>()
        for (k in amtSpans.indices) {
            if (keep.isNotEmpty() && ws.subList(pos[keep.last()][1], pos[k][0]).any { it in correction }) keep[keep.size - 1] = k else keep.add(k)
        }
        val amounts = keep.mapNotNull { parseAmount(amtSpans[it]) }
        val items = (spans["ITEM"] ?: emptyList()).map { sp ->
            sp.filterIndexed { k, w -> !(w in lead && sp.subList(0, k + 1).all { it in lead }) }.joinToString(" ")
        }.filter { it.isNotEmpty() && it !in lead }
        val heard = ArrayList<String>(); var start = -1
        for (k in 0..ws.size) {
            val isItem = k < ws.size && lab[k] == "ITEM"
            if (isItem && start < 0) start = k
            if (!isItem && start >= 0) {
                var a = start
                while (a < k && (ws[a] in lead || ws[a] in OWNER)) a++
                if (a < k) heard.add((a until k).joinToString(" ") { SmartText.translit(raw.getOrElse(it) { ws[it] }) })
                start = -1
            }
        }
        // "my credit card … from SBI": the phrase that names something specific wins over a generic one.
        val accSpan = (spans["ACC"]?.firstOrNull { sp -> sp.any { it !in genericAcc && it !in OWNER } } ?: spans["ACC"]?.firstOrNull())?.joinToString(" ") ?: (if ("cash" in ws) "cash" else "")
        val tp = typeProbs(ws); var kind = types[argmax(tp)]
        // Spending is far more common than income: a hesitant "income" with no income word ("got", "received", "salary"…) is spending.
        if (kind == "income" && tp.max() < 0.75f && ws.none { it in INCOME_CUES }) kind = "expense"
        val itemFolded = items.joinToString(" ").ifEmpty { ws.filterIndexed { k, _ -> lab[k] == "O" }.joinToString(" ") }
        val itemRaw = raw.filterIndexed { k, _ -> lab[k] == "ITEM" }.joinToString(" ").ifEmpty { raw.filterIndexed { k, _ -> lab[k] == "O" }.joinToString(" ") }
        val cats = if (kind == "transfer") emptyList() else categorise(itemFolded, itemRaw, if (kind == "income") incomeCategories else expenseCategories)
        return Result(kind, tp.max(), if (amounts.isEmpty()) null else amounts.sum(), items, resolveAccount(accSpan, accounts), daysAgo(ws), cats, ws.zip(lab), heard.takeIf { it.size == items.size } ?: items)
    }

    /**
     * "on my credit card", "from my account", "with my HDFC card": the card or account word and the generic words before it
     * name the account, not something bought. "gift card" and "credit card bill" (an item right before or after "card") are left alone.
     */
    private fun fixAccountPhrases(ws: List<String>, tagged: List<String>): List<String> {
        val lab = tagged.toMutableList()
        for (k in ws.indices) {
            if (ws[k] !in ACC_HEADS) continue
            if (k > 0 && lab[k - 1] == "ITEM" && ws[k - 1] !in genericAcc && ws[k - 1] !in OWNER) continue
            // "credit card bill", "card cashback", "card late fee": the card is what the money is about, not how it was paid.
            if (k + 1 < ws.size && (tagged[k + 1] == "ITEM" || ws[k + 1] in CARD_THINGS)) continue
            var a = k
            while (a > 0 && lab[a - 1] != "AMT" && lab[a - 1] != "DATE" && (ws[a - 1] in genericAcc || ws[a - 1] in OWNER || lab[a - 1] == "ACC")) a--
            for (j in a..k) lab[j] = "ACC"
        }
        return lab
    }


    private fun argmax(a: FloatArray): Int { var b = 0; for (i in 1 until a.size) if (a[i] > a[b]) b = i; return b }
}

private val ACC_HEADS = setOf("card", "acount", "account")
private val OWNER = setOf("my", "mera", "meri", "mere", "the", "our")
/** Folded spellings of words that mark money coming in. */
private val INCOME_CUES = setOf("got", "get", "recived", "recieved", "receive", "recive", "credited", "credit", "salary", "earned", "income",
    "bonus", "cashback", "interest", "refund", "returned", "mila", "mili", "mile", "aya", "ayi", "aye", "ai", "prapt", "jama")
/** Words after "card"/"account" that make it the subject ("card bill") rather than how something was paid. */
private val CARD_THINGS = setOf("bil", "bill", "payment", "fe", "fee", "emi", "statement", "dues", "due", "limit", "cashback", "charges", "charge")
