// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.dictation.desktop.DictationException
import helium314.keyboard.latin.dictation.desktop.PendingClip
import helium314.keyboard.latin.dictation.desktop.PendingDictations
import helium314.keyboard.latin.dictation.desktop.ServerDictationEngine
import java.net.URI
import helium314.keyboard.latin.dictation.desktop.DictationEngine as Engine

/**
 * The desktop's Whisper (Herdr's dictation server) behind [DictationRecognizer]. One session lasts
 * from the tap to the next: partials are the whole text so far, and the one result comes after
 * [stopListening]. While the desktop is away the session records into the keyboard's storage
 * ([Events.onOffline]); on stop the recording waits in [PendingDictations] and its text is placed
 * later ([Events.onPending]).
 */
class WhisperRecognizer(
    context: Context,
    uri: URI,
    /** The field the session is for (see [DictationController.fieldKey]), kept with a recording. */
    private val target: String,
    private val events: Events,
) : DictationRecognizer {
    /** What [RecognitionListener] has no words for. */
    interface Events {
        /** The desktop is unreachable: recording for later, and the live partial is withdrawn. */
        fun onOffline(source: WhisperRecognizer)
        /** The recording was saved (maybe after [cancel]); its text arrives through [PendingDictations]. */
        fun onPending(source: WhisperRecognizer, clip: PendingClip)
    }

    private val engine = ServerDictationEngine(context, uri, pending(context))
    private var listener: RecognitionListener? = null
    private var running = false
    /** Recording for later: a [cancel] keeps the recording instead of dropping it. */
    private var offline = false
    /** Cancelled while offline: the session is finishing on its own, for later. */
    private var parked = false

    override fun setListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    override fun startListening(intent: Intent) {
        if (running || parked) return
        running = true
        offline = false
        engine.start(target, object : Engine.Listener {
            override fun onPartial(text: String) {
                listener?.onPartialResults(results(text))
            }

            override fun onFinal(text: String) {
                listener?.onResults(results(text))
            }

            override fun onState(state: Engine.State) {
                when (state) {
                    Engine.State.LISTENING -> listener?.onReadyForSpeech(Bundle())
                    Engine.State.OFFLINE_RECORDING -> {
                        offline = true
                        events.onOffline(this@WhisperRecognizer)
                    }
                    Engine.State.IDLE, Engine.State.PENDING -> running = false
                    else -> {}
                }
            }

            override fun onError(error: DictationException) {
                running = false
                listener?.onError(when (error.reason()) {
                    DictationException.Reason.PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    DictationException.Reason.AUDIO -> SpeechRecognizer.ERROR_AUDIO
                    else -> SpeechRecognizer.ERROR_CLIENT
                })
            }

            override fun onPending(clip: PendingClip) {
                events.onPending(this@WhisperRecognizer, clip)
                listener?.onResults(results(""))
            }
        })
    }

    override fun stopListening() = engine.stop()

    /**
     * Ends the session (keyboard hidden, typing, cursor moved). Live, the text shown stays and the
     * audio is dropped; recording offline, nothing was heard yet, so the recording is kept.
     */
    override fun cancel() {
        if (parked) return
        if (running && offline) {
            parked = true
            engine.stop()
            return
        }
        running = false
        engine.cancel()
    }

    override fun destroy() = cancel()

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, if (text.isEmpty()) arrayListOf() else arrayListOf(text))
    }

    companion object {
        /** The process's waiting recordings; none are ever transcribed without a server set. */
        fun pending(context: Context): PendingDictations =
            PendingDictations.shared(context, DictationEngine.serverUri(context))

        /** Main thread: the mic keys' dot for what [pending] holds ([MicKeyState.Badge]). */
        fun showBadge(context: Context) {
            val pending = pending(context)
            MicKeyState.setBadge(when {
                pending.ready().isNotEmpty() -> MicKeyState.Badge.READY
                pending.waiting().isNotEmpty() -> MicKeyState.Badge.WAITING
                else -> MicKeyState.Badge.NONE
            })
        }
    }
}
