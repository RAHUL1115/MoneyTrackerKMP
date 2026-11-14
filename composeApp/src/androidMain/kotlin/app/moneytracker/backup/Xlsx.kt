package app.moneytracker.backup

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Minimal Office Open XML spreadsheet support: one sheet, inline strings, plain numbers.
 * Enough for exporting transactions and importing simple sheets (including ones saved by
 * Excel / Google Sheets / LibreOffice, which use shared strings and date serials).
 */
object Xlsx {
    private val number = Regex("""^-?\d+(\.\d+)?$""")

    fun write(rows: List<List<String>>, out: OutputStream, sheetName: String = "Transactions") {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""")
            val sheet = StringBuilder()
            sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { r, row ->
                sheet.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    val ref = colName(c) + (r + 1)
                    val style = if (r == 0) " s=\"1\"" else ""
                    if (r > 0 && number.matches(v)) sheet.append("<c r=\"$ref\"$style><v>$v</v></c>")
                    else if (v.isNotEmpty()) sheet.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
    }

    fun read(input: InputStream): List<List<String>> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()
        val sheet = files["xl/worksheets/sheet1.xml"] ?: files.entries.filter { it.key.startsWith("xl/worksheets/sheet") }.minByOrNull { it.key }?.value
            ?: error("No worksheet found in this file")
        return readSheet(sheet, shared)
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = mutableListOf<String>()
        var current: StringBuilder? = null
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> current = StringBuilder(); "t" -> inT = true }
                XmlPullParser.TEXT -> if (inT) current?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { out += current.toString(); current = null } }
            }
        }
        return out
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val p = parser(bytes)
        val sheet = SheetReader(shared)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> sheet.start(p.name, ref = p.getAttributeValue(null, "r"), cellType = p.getAttributeValue(null, "t"))
                XmlPullParser.TEXT -> sheet.text(p.text)
                XmlPullParser.END_TAG -> sheet.end(p.name)
            }
        }
        return sheet.rows
    }

    /** Collects a worksheet's rows from its XML events: cells land at their column, gaps stay empty. */
    private class SheetReader(private val shared: List<String>) {
        val rows = mutableListOf<List<String>>()
        private var row: MutableMap<Int, String>? = null
        private var col = 0
        private var type: String? = null
        private var value = StringBuilder()
        private var capture = false

        fun start(tag: String, ref: String?, cellType: String?) {
            when (tag) {
                "row" -> row = mutableMapOf()
                "c" -> {
                    col = ref?.let { colIndex(it) } ?: (row?.keys?.maxOrNull()?.plus(1) ?: 0)
                    type = cellType
                    value = StringBuilder()
                }
                "v", "t" -> capture = true
            }
        }

        fun text(text: String) {
            if (capture) value.append(text)
        }

        fun end(tag: String) {
            when (tag) {
                "v", "t" -> capture = false
                "c" -> row?.put(col, cellText(value.toString()))
                "row" -> row?.let { r -> rows += List((r.keys.maxOrNull() ?: -1) + 1) { r[it].orEmpty() } }
            }
        }

        /** Shared-string cells hold an index, boolean cells 0 or 1; everything else is the text itself. */
        private fun cellText(raw: String): String = when (type) {
            "s" -> shared.getOrElse(raw.toIntOrNull() ?: -1) { "" }
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            else -> raw
        }
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }
        return sb.toString()
    }

    fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref.takeWhile { it.isLetter() }) n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
