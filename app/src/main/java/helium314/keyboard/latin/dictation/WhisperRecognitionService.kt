// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.dictation.desktop.DictationException
import helium314.keyboard.latin.dictation.desktop.PendingClip
import helium314.keyboard.latin.utils.Log

/**
 * The desktop's Whisper as the phone's speech recognizer (secure voice_recognition_service), so
 * other apps' [SpeechRecognizer]s dictate on it too. One utterance per request ([WhisperUtterance]).
 *
 * While the desktop is away the request is still recorded; the app gets
 * [SpeechRecognizer.ERROR_NETWORK] (it cannot take a late result) and the recording waits in the
 * keyboard's queue, offered from the mic key once transcribed.
 */
class WhisperRecognitionService : RecognitionService() {
    private var utterance: WhisperUtterance? = null

    override fun onStartListening(intent: Intent, listener: Callback) {
        if (utterance?.done == false) return call { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        val uri = DictationEngine.serverUri(this)
            ?: return call { listener.error(SpeechRecognizer.ERROR_SERVER) }
        val caller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            packageManager.getNameForUid(listener.callingUid) else null
        // The microphone is used for the calling app (Android checked its RECORD_AUDIO).
        val micContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        } catch (e: RuntimeException) {
            Log.w(TAG, "can't attribute the microphone to the caller", e)
            null
        } else null
        val partials = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        utterance = WhisperUtterance(this, micContext, uri, "recognizer/${caller ?: "app"}",
            WhisperUtterance.silenceMs(intent.extras), object : WhisperUtterance.Events {
                override fun onReady() = call { listener.readyForSpeech(Bundle()) }
                override fun onSpeechStart() = call { listener.beginningOfSpeech() }
                // Apps draw about 0..10.
                override fun onLevel(dbfs: Float) = call { listener.rmsChanged((dbfs + 60f) / 5f) }
                override fun onPartial(text: String) {
                    if (partials) call { listener.partialResults(results(text)) }
                }
                override fun onOffline() {
                    if (partials) call { listener.partialResults(results("")) }
                }
                override fun onFinishing() = call { listener.endOfSpeech() }
                override fun onResult(text: String) = call {
                    if (text.isEmpty()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    else listener.results(results(text))
                }
                override fun onSaved(clip: PendingClip) = call { listener.error(SpeechRecognizer.ERROR_NETWORK) }
                override fun onError(error: DictationException) = call {
                    listener.error(when (error.reason()) {
                        DictationException.Reason.PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        DictationException.Reason.AUDIO -> SpeechRecognizer.ERROR_AUDIO
                        DictationException.Reason.UNREACHABLE -> SpeechRecognizer.ERROR_NETWORK
                        else -> SpeechRecognizer.ERROR_CLIENT
                    })
                }
            }).also { it.start() }
    }

    override fun onStopListening(listener: Callback) {
        utterance?.stop()
    }

    override fun onCancel(listener: Callback) {
        utterance?.cancel()
        utterance = null
    }

    override fun onDestroy() {
        utterance?.cancel()
        utterance = null
        super.onDestroy()
    }

    /** The app may be gone; nothing to tell it then. */
    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            utterance?.cancel()
        }
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, if (text.isEmpty()) arrayListOf() else arrayListOf(text))
        if (text.isNotEmpty()) putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
    }

    private companion object {
        const val TAG = "WhisperRecognition"
    }
}
