package app.moneytracker

import kotlinx.datetime.Clock

/**
 * The app's one logger. Shared code calls [Log.d]/[i]/[w]/[e]; each platform installs a [Sink] at start-up
 * (Android: Logcat plus a small rotating file in the app's private storage).
 *
 * Privacy rule for every call site: never log message text, amounts, notes, merchant, account or category names,
 * passcodes or file contents. Log what happened and why (counts, ids, outcomes, timings, sender ids of banks), so a
 * log can be shared to diagnose a problem without exposing anyone's money.
 *
 * Messages are lambdas, so nothing is built for a level that is switched off.
 */
object Log {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    /** Lowest level that is written. Debug builds log everything; release builds start at INFO. */
    var minLevel: Level = Level.INFO

    private var sinks: List<Sink> = emptyList()

    /** Last lines kept in memory (newest last), for tests and for showing or sharing a recent log. */
    private val ring = ArrayDeque<String>()
    private const val RING_SIZE = 400
    private val lock = Any()

    fun install(vararg sink: Sink) { sinks = sink.toList() }

    fun d(tag: String, message: () -> String) = log(Level.DEBUG, tag, null, message)
    fun i(tag: String, message: () -> String) = log(Level.INFO, tag, null, message)
    fun w(tag: String, error: Throwable? = null, message: () -> String) = log(Level.WARN, tag, error, message)
    fun e(tag: String, error: Throwable? = null, message: () -> String) = log(Level.ERROR, tag, error, message)

    /** Runs [block]; a failure is logged at WARN with [what] and returns null instead of throwing. */
    inline fun <T> attempt(tag: String, what: String, block: () -> T): T? =
        try { block() } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            w(tag, t) { "$what failed" }; null
        }

    /** Times [block] and logs how long it took at DEBUG. */
    inline fun <T> timed(tag: String, what: String, block: () -> T): T {
        val start = Clock.System.now()
        val result = block()
        d(tag) { "$what took ${(Clock.System.now() - start).inWholeMilliseconds} ms" }
        return result
    }

    /** A message sender as it may appear in a log: bank ids (AD-HDFCBK) as they are, phone numbers hidden. */
    fun sender(s: String): String = if (s.any { it.isLetter() }) s.take(20) else "a phone number"

    fun recent(): List<String> = synchronized(lock) { ring.toList() }

    fun log(level: Level, tag: String, error: Throwable?, message: () -> String) {
        if (level < minLevel) return
        val text = try { message() } catch (t: Throwable) { "(log message failed: ${t::class.simpleName})" }
        val line = buildString {
            append(Clock.System.now().toString().take(23)).append(' ').append(level.letter).append(' ').append(tag).append(": ").append(text)
            if (error != null) append(" | ").append(error::class.simpleName).append(": ").append(error.message.orEmpty().take(200))
        }
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }
        sinks.forEach { s -> try { s.write(level, tag, text, error) } catch (_: Throwable) {} }
    }
}
