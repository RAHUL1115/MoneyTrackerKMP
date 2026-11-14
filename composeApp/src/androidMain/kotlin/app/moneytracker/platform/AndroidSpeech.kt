package app.moneytracker.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.moneytracker.core.Log
import app.moneytracker.core.SpeechError
import app.moneytracker.core.SpeechListener
import app.moneytracker.core.SpeechSession

/**
 * MoneyTracker's own listening: Android's speech recogniser driven from the app, so the person sees the app's sheet
 * rather than a system popup. Uses the phone's on-device recogniser when it has one.
 */
internal class AndroidSpeech(private val ctx: Context) {
    private val debuggable get() = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val recognizerAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Debug builds can always listen: on a phone without a recogniser (emulators) the words come from adb. */
    val available: Boolean get() = recognizerAvailable || debuggable

    fun start(listener: SpeechListener): SpeechSession? = when {
        recognizerAvailable -> RealSession(listener).also { it.begin() }
        debuggable -> FakeSession(listener)
        else -> null
    }

    private inner class RealSession(private val l: SpeechListener) : SpeechSession, RecognitionListener {
        private val onDevice = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        private val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        private var lastPartial = ""
        private var done = false
        private var stopping = false

        fun begin() {
            recognizer.setRecognitionListener(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                // Let people pause to think: wait longer before deciding they've finished.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            if (!recognizerCall("start") { recognizer.startListening(intent) }) finishWithError(SpeechError.OTHER)
        }

        override fun stop() { if (!done) { stopping = true; recognizerCall("stop") { recognizer.stopListening() } } }
        override fun cancel() { done = true; recognizerCall("cancel") { recognizer.cancel(); recognizer.destroy() } }

        private fun finish(text: String) {
            if (done) return
            done = true
            recognizerCall("destroy") { recognizer.destroy() }
            l.onFinal(text)
        }

        private fun finishWithError(e: SpeechError) {
            if (done) return
            done = true
            Log.w("Speech") { "listening ended: $e" }
            recognizerCall("destroy") { recognizer.destroy() }
            l.onError(e)
        }

        /**
         * Runs a call into the phone's speech service, which belongs to the phone maker and can throw any runtime
         * exception across the binder. A failure is logged and only ends listening; returns whether the call worked.
         */
        @Suppress("TooGenericExceptionCaught")
        private inline fun recognizerCall(what: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: RuntimeException) {
            Log.w("Speech", e) { "recogniser $what failed" }
            false
        }

        // The sheet shows only levels, partial words and the result, so these callbacks have nothing to do.
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        // rms is roughly -2..10 dB.
        override fun onRmsChanged(rmsdB: Float) { if (!done) l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty() && !done) { lastPartial = t; l.onPartial(t) }
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            finish(t.ifEmpty { lastPartial })
        }

        override fun onError(error: Int) {
            // Stopping early or a pause with words already heard still counts as an answer.
            val endedByPause = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (lastPartial.isNotEmpty() && (stopping || endedByPause)) {
                finish(lastPartial)
                return
            }
            finishWithError(when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechError.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
                else -> SpeechError.OTHER
            })
        }
    }

    /**
     * Debug builds only, for phones with no recogniser (emulators). Feed words with
     * `adb shell am broadcast -a <applicationId>.FAKE_SPEECH --es text "1200 for new shoes"` (add `--ez final true` to finish).
     */
    private inner class FakeSession(private val l: SpeechListener) : SpeechSession {
        private var text = ""
        private var done = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (done || i == null) return
                i.getStringExtra("text")?.let { text = it; l.onPartial(it); l.onLevel(0.8f) }
                if (i.getBooleanExtra("final", false)) stop()
            }
        }

        init {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ctx.packageName + ".FAKE_SPEECH"), ContextCompat.RECEIVER_EXPORTED)
        }

        private fun close() {
            done = true
            try { ctx.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { Log.d("Speech") { "fake receiver already gone: ${e::class.simpleName}" } }
        }
        override fun stop() {
            if (done) return
            close()
            if (text.isBlank()) l.onError(SpeechError.NO_MATCH) else l.onFinal(text)
        }
        override fun cancel() { if (!done) close() }
    }
}
