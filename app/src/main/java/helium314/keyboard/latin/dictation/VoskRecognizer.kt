// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.SpeechService

/**
 * The keyboard's own recognizer: Vosk on the device, streaming. Partial text while you speak,
 * a result per utterance, and it keeps listening (a further [startListening] is a no-op) until
 * [stopListening] (delivers the last words) or [cancel].
 */
class VoskRecognizer(private val context: Context) : DictationRecognizer {
    private var listener: RecognitionListener? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var starting = false
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())

    override fun setListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    override fun startListening(intent: Intent) {
        if (destroyed || starting) return
        if (service != null) { // already listening, it continues by itself: just say so again
            main.post { if (service != null) listener?.onReadyForSpeech(Bundle()) }
            return
        }
        starting = true
        VoskModel.load(context) { model ->
            starting = false
            if (destroyed || service != null) return@load
            if (model == null) {
                listener?.onError(SpeechRecognizer.ERROR_CLIENT)
                return@load
            }
            try {
                val rec = Recognizer(model, SAMPLE_RATE)
                recognizer = rec
                service = SpeechService(rec, SAMPLE_RATE).also { it.startListening(voskListener) }
            } catch (e: Exception) { // e.g. the microphone is busy
                Log.w(TAG, "can't start listening", e)
                release()
                listener?.onError(SpeechRecognizer.ERROR_AUDIO)
                return@load
            }
            listener?.onReadyForSpeech(Bundle())
        }
    }

    override fun stopListening() {
        val s = service
        if (s == null) { // nothing heard yet
            listener?.onResults(results(""))
            return
        }
        s.stop() // ends with onFinalResult
    }

    override fun cancel() = release()

    override fun destroy() {
        destroyed = true
        listener = null
        release()
    }

    private fun release() {
        service?.let {
            try {
                it.cancel()
                it.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "error stopping", e)
            }
        }
        service = null
        recognizer?.close()
        recognizer = null
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = field(hypothesis, "partial")
            if (text.isNotEmpty()) listener?.onPartialResults(results(text))
        }

        override fun onResult(hypothesis: String?) {
            val text = field(hypothesis, "text")
            if (text.isNotEmpty()) listener?.onResults(results(text)) // empty: silence, keep listening
        }

        override fun onFinalResult(hypothesis: String?) {
            val l = listener
            release()
            l?.onResults(results(field(hypothesis, "text")))
        }

        override fun onError(exception: Exception?) {
            Log.w(TAG, "recognizer error", exception)
            val l = listener
            release()
            l?.onError(SpeechRecognizer.ERROR_AUDIO)
        }

        override fun onTimeout() {}
    }

    private fun field(json: String?, name: String): String = try {
        if (json == null) "" else JSONObject(json).optString(name).trim()
    } catch (e: Exception) {
        ""
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, if (text.isEmpty()) arrayListOf() else arrayListOf(text))
    }

    companion object {
        private const val TAG = "VoskRecognizer"
        private const val SAMPLE_RATE = 16000f
    }
}
