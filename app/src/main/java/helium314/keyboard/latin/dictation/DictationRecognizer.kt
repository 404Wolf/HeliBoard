// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Intent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * What [DictationController] needs from a recognizer: Android's [SpeechRecognizer] (a recognition
 * service) or the desktop's Whisper ([WhisperRecognizer]). Results arrive through
 * Android's [RecognitionListener] either way, on the main thread.
 */
interface DictationRecognizer {
    fun setListener(listener: RecognitionListener?)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

class SystemDictationRecognizer(private val recognizer: SpeechRecognizer) : DictationRecognizer {
    override fun setListener(listener: RecognitionListener?) = recognizer.setRecognitionListener(listener)
    override fun startListening(intent: Intent) = recognizer.startListening(intent)
    override fun stopListening() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}
