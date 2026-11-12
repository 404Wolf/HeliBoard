// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.dictation.desktop.DictationException
import helium314.keyboard.latin.dictation.desktop.PendingClip
import helium314.keyboard.latin.dictation.desktop.ServerDictationEngine
import java.net.URI
import helium314.keyboard.latin.dictation.desktop.DictationEngine as Engine

/**
 * One utterance on the desktop's Whisper for another app ([WhisperRecognitionService],
 * [RecognizeSpeechActivity]). With [silenceMs] it ends by itself after a pause, the way apps
 * using a [android.speech.SpeechRecognizer] expect; without, it runs until [stop] (a tap), like
 * the keyboard's own dictation. Either way it gives up with no text if nothing is said at first.
 *
 * Speech is found from the microphone's level (so it also works while the desktop is away): the
 * quietest level seen is the room's noise, and speech is anything clearly above it.
 * While the desktop is away the session records as the keyboard does, and on stop the recording
 * waits in the keyboard's queue ([Events.onSaved]); its text is offered later by the keyboard.
 *
 * Main thread only, including every callback.
 */
class WhisperUtterance(
    context: Context,
    /** Records on behalf of another app (see [ServerDictationEngine]), or null for our own. */
    micContext: Context?,
    uri: URI,
    /** Who asked, kept with a recording made while the desktop is away. */
    private val target: String,
    /** Silence after speech that ends the utterance; null: only [stop] ends it. */
    private val silenceMs: Long?,
    private val events: Events,
) {
    interface Events {
        /** Recording: speak now (the desktop may still be connecting). */
        fun onReady()
        fun onSpeechStart()
        /** The microphone level, dB below full scale, about every 100 ms. */
        fun onLevel(dbfs: Float)
        /** The text so far (live only). */
        fun onPartial(text: String)
        /** The desktop is away: recording for later, the partial text is withdrawn. */
        fun onOffline()
        /** Speech ended: waiting for the final text. */
        fun onFinishing()
        /** The final text; empty when nothing was said. */
        fun onResult(text: String)
        /** The desktop is away: the recording waits in the keyboard's queue. */
        fun onSaved(clip: PendingClip)
        fun onError(error: DictationException)
    }

    private val app: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val engine = ServerDictationEngine(context, micContext, uri, WhisperRecognizer.pending(context))
    private val startedAt = SystemClock.uptimeMillis()
    private var floor = Float.NaN
    private var heard = false
    private var lastSpeechAt = 0L
    private var lastPartial = ""
    private var stopped = false
    /** Over: no more callbacks. */
    var done = false
        private set

    fun start() {
        engine.setLevelListener { level -> main.post { onLevel(level) } }
        engine.start(target, object : Engine.Listener {
            override fun onPartial(text: String) {
                if (done) return
                if (text.isNotEmpty() && text != lastPartial) speech()
                lastPartial = text
                events.onPartial(text)
            }

            override fun onFinal(text: String) {
                if (done) return
                finish()
                events.onResult(text.trim())
            }

            override fun onState(state: Engine.State) {
                if (done) return
                when (state) {
                    Engine.State.TRANSCRIBING -> events.onFinishing()
                    Engine.State.OFFLINE_RECORDING -> events.onOffline()
                    else -> {}
                }
            }

            override fun onError(error: DictationException) {
                if (done) return
                finish()
                events.onError(error)
            }

            override fun onPending(clip: PendingClip) {
                if (done) return
                finish()
                events.onSaved(clip)
                WhisperRecognizer.showBadge(app)
            }
        })
        if (!done) events.onReady()
    }

    /** Ends the utterance now; the final text (or the saved recording) follows. */
    fun stop() {
        if (done || stopped) return
        stopped = true
        engine.stop()
    }

    /** Drops the utterance: no text, nothing kept, no more callbacks. */
    fun cancel() {
        if (done) return
        finish()
        engine.cancel()
    }

    private fun finish() {
        done = true
        engine.setLevelListener(null)
    }

    private fun speech() {
        if (!heard) {
            heard = true
            events.onSpeechStart()
        }
        lastSpeechAt = SystemClock.uptimeMillis()
    }

    private fun onLevel(level: Float) {
        if (done) return
        events.onLevel(level)
        if (stopped) return
        // The noise floor follows the quietest level, creeping up so a louder room is learned.
        floor = if (floor.isNaN() || level < floor) level else floor + FLOOR_RISE_DB
        if (level > floor + SPEECH_ABOVE_FLOOR_DB && level > SPEECH_MIN_DBFS) speech()
        val now = SystemClock.uptimeMillis()
        when {
            silenceMs != null && heard && now - lastSpeechAt > silenceMs -> stop()
            !heard && now - startedAt > NO_SPEECH_MS -> {
                // Nothing said: neither text nor a recording to keep.
                cancel()
                events.onResult("")
            }
            silenceMs != null && now - startedAt > MAX_MS -> stop()
        }
    }

    companion object {
        const val DEFAULT_SILENCE_MS = 2000L
        private const val NO_SPEECH_MS = 8000L
        private const val MAX_MS = 120_000L
        private const val FLOOR_RISE_DB = 0.05f
        private const val SPEECH_ABOVE_FLOOR_DB = 12f
        private const val SPEECH_MIN_DBFS = -60f

        /** The pause that ends an utterance, from a recognizer intent's extras (bounded). */
        fun silenceMs(extras: android.os.Bundle?): Long {
            val asked = extras?.getLong(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L) ?: 0L
            return if (asked > 0) asked.coerceIn(500L, 10_000L) else DEFAULT_SILENCE_MS
        }
    }
}
