// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LastComposedWord
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * In-keyboard dictation with [SpeechRecognizer]: partial results stream into the editor as
 * composing text, final results are committed, and listening restarts after each utterance
 * until stopped (mic tap, "stop listening", silence timeout, typing, cursor move, keyboard closed).
 * English voice commands ([DictationCommandParser]) are applied instead of inserted.
 *
 * All methods must be called on the main thread.
 */
class DictationController(private val ime: LatinIME, private val inputLogic: InputLogic) : RecognitionListener {
    private val connection get() = inputLogic.mConnection
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var engine = DictationEngine.GOOGLE
    private var component: ComponentName? = null
    private var triedSystemFallback = false
    private var preferOffline = false
    private var sendLanguage = true
    private var consecutiveErrors = 0

    /** Whether a dictation session is running (also while finishing the last utterance). */
    var isActive = false
        private set
    private var stopping = false
    private var stopReason = StopReason.USER

    // current utterance
    private var composing = ""
    private var contextBefore: String? = null

    // dictated edits of this session, for "delete that" and "undo"
    private sealed interface Edit { val text: String }
    private data class Inserted(override val text: String, val removedBefore: String) : Edit
    private data class Deleted(override val text: String, val removedBefore: String) : Edit
    private val history = ArrayDeque<Edit>()

    private var lastActivity = 0L
    private var commandsEnabled = true
    private var autoSend = false
    private var silenceTimeoutMs = Long.MAX_VALUE

    private var indicator: DictationIndicatorView? = null

    /** True while dictation itself sends key codes (undo, enter), which must not stop dictation. */
    var isDispatchingKey = false
        private set
    private var tolerateSelectionChangesUntil = 0L

    private enum class StopReason { USER, SILENCE, COMMAND }

    // ---- public interface for LatinIME ----

    /** Mic key tapped. */
    fun toggle() {
        if (isActive) stop(StopReason.USER) else start()
    }

    /** Long-press on the mic key: choose the recognition engine. */
    fun showEnginePicker() {
        val token = KeyboardSwitcher.getInstance().mainKeyboardView?.windowToken ?: return
        createEnginePickerDialog(ime, token) { chosen ->
            ime.prefs().edit().putString(Settings.PREF_DICTATION_ENGINE, chosen.prefValue).apply()
            if (isActive) { // restart with the new engine
                cancel()
                start()
            }
        }.show()
    }

    /** Stop right away, keeping text already in the editor (keyboard hidden, input finished, typing). */
    fun cancel() {
        if (!isActive) return
        handler.removeCallbacksAndMessages(null)
        finishComposing()
        destroyRecognizer()
        endSession()
    }

    /**
     * Called before LatinIME handles a selection update.
     * @return true if the update was caused by dictation and must not be processed further
     */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                          composingSpanStart: Int, composingSpanEnd: Int): Boolean {
        if (!isActive) return false
        if (connection.isBelatedExpectedUpdate(oldSelStart, newSelStart, oldSelEnd, newSelEnd, composingSpanStart, composingSpanEnd))
            return true
        // after "send" or "undo" the app changes the text (e.g. clears a chat field): keep listening
        if (composing.isEmpty() && SystemClock.uptimeMillis() < tolerateSelectionChangesUntil) return false
        // the user (or app) moved the cursor: stop, the composing text belongs to the old position
        cancel()
        return false
    }

    fun onDestroy() = cancel()

    // ---- session ----

    private fun start() {
        if (ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DictationPermissionActivity.request(ime)
            return
        }
        engine = DictationEngine.selected(ime)
        component = DictationEngine.resolve(ime, engine)
        if (!DictationEngine.usable(ime, component)) {
            // no Google service and no system default selected (createSpeechRecognizer would fail
            // with "no selected voice recognition service"): use the voice input method, if any
            if (!switchToVoiceIme()) toast(R.string.dictation_unavailable)
            return
        }
        val prefs = ime.prefs()
        commandsEnabled = prefs.getBoolean(Settings.PREF_DICTATION_COMMANDS, Defaults.PREF_DICTATION_COMMANDS)
        autoSend = prefs.getBoolean(Settings.PREF_DICTATION_AUTO_SEND, Defaults.PREF_DICTATION_AUTO_SEND)
        val timeout = prefs.getInt(Settings.PREF_DICTATION_SILENCE_TIMEOUT, Defaults.PREF_DICTATION_SILENCE_TIMEOUT)
        silenceTimeoutMs = if (timeout > 60 || timeout <= 0) Long.MAX_VALUE else timeout * 1000L
        triedSystemFallback = false
        preferOffline = false
        sendLanguage = true
        consecutiveErrors = 0
        history.clear()
        stopping = false

        // commit the word being typed, dictation continues after it
        connection.beginBatchEdit()
        inputLogic.commitTyped(Settings.getValues(), LastComposedWord.NOT_A_SEPARATOR)
        connection.endBatchEdit()

        if (!createRecognizer()) {
            if (!switchToVoiceIme()) toast(R.string.dictation_unavailable)
            return
        }
        isActive = true
        showIndicator()
        lastActivity = SystemClock.uptimeMillis()
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        startListening()
    }

    private fun createRecognizer(): Boolean {
        destroyRecognizer()
        recognizer = try {
            if (component != null) SpeechRecognizer.createSpeechRecognizer(ime, component)
            else SpeechRecognizer.createSpeechRecognizer(ime)
        } catch (e: Exception) {
            Log.w(TAG, "can't create speech recognizer", e)
            null
        }
        recognizer?.setRecognitionListener(this)
        updateIndicatorEngine()
        return recognizer != null
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.setRecognitionListener(null)
            r.cancel()
            r.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "error destroying recognizer", e)
        }
    }

    private fun startListening() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ime.packageName)
            // Google's service: dictation mode (longer utterances, spoken punctuation)
            putExtra(EXTRA_DICTATION_MODE, true)
            if (sendLanguage) {
                val tag = locale().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
        }
        contextBefore = null
        composing = ""
        try {
            r.startListening(intent)
            indicator?.setState(DictationIndicatorView.State.STARTING)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun restartListening(delayMs: Long = RESTART_DELAY_MS) {
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    private val restart = Runnable { if (isActive && !stopping) startListening() }

    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            if (!stopping && SystemClock.uptimeMillis() - lastActivity > silenceTimeoutMs) {
                stop(StopReason.SILENCE)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /** Stop listening, but let the recognizer deliver the result for what was already said. */
    private fun stop(reason: StopReason) {
        if (!isActive) return
        stopReason = reason
        if (stopping) { // second tap: stop now
            finalizeStop()
            return
        }
        stopping = true
        handler.removeCallbacks(restart)
        indicator?.setState(DictationIndicatorView.State.FINISHING)
        if (composing.isEmpty()) {
            finalizeStop()
            return
        }
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            finalizeStop()
            return
        }
        handler.postDelayed(::finalizeStop, FINISH_TIMEOUT_MS)
    }

    private fun finalizeStop() {
        if (!isActive) return
        handler.removeCallbacksAndMessages(null)
        finishComposing()
        destroyRecognizer()
        endSession()
        if (autoSend && stopReason != StopReason.USER) performSend()
    }

    private fun endSession() {
        isActive = false
        stopping = false
        composing = ""
        contextBefore = null
        hideIndicator()
        ime.updateShiftStateAfterDictation()
    }

    // ---- RecognitionListener ----

    override fun onReadyForSpeech(params: Bundle?) {
        indicator?.setState(if (preferOffline) DictationIndicatorView.State.LISTENING_OFFLINE else DictationIndicatorView.State.LISTENING)
    }

    override fun onBeginningOfSpeech() {
        lastActivity = SystemClock.uptimeMillis()
    }

    override fun onRmsChanged(rmsdB: Float) {
        indicator?.setLevel(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onPartialResults(partialResults: Bundle?) {
        if (!isActive) return
        val text = firstResult(partialResults) ?: return
        lastActivity = SystemClock.uptimeMillis()
        consecutiveErrors = 0
        showPartial(text)
    }

    override fun onResults(results: Bundle?) {
        if (!isActive) return
        consecutiveErrors = 0
        val text = firstResult(results)
        if (text != null) {
            lastActivity = SystemClock.uptimeMillis()
            commitFinal(text)
        } else {
            finishComposing()
        }
        if (stopping) finalizeStop()
        else if (isActive) restartListening()
    }

    override fun onError(error: Int) {
        if (!isActive) return
        Log.i(TAG, "recognizer error $error (engine $engine, offline $preferOffline)")
        if (stopping) {
            finalizeStop()
            return
        }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // nothing (more) said: keep listening, the watchdog ends long silences
                finishComposing()
                restartListening()
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS, ERROR_LANGUAGE_UNAVAILABLE -> {
                finishComposing()
                if (!preferOffline) {
                    preferOffline = true
                    toast(R.string.dictation_offline)
                    restartListening()
                } else if (fallBackToSystemRecognizer()) {
                    restartListening()
                } else {
                    toast(R.string.dictation_no_network)
                    cancel()
                }
            }
            ERROR_LANGUAGE_NOT_SUPPORTED -> {
                finishComposing()
                if (sendLanguage) {
                    sendLanguage = false
                    restartListening()
                } else if (fallBackToSystemRecognizer()) {
                    restartListening()
                } else {
                    giveUp()
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                cancel()
                DictationPermissionActivity.request(ime)
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                toast(R.string.dictation_audio_error)
                cancel()
            }
            else -> { // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, unknown
                finishComposing()
                consecutiveErrors++
                if (consecutiveErrors > MAX_RETRIES) {
                    if (fallBackToSystemRecognizer()) {
                        consecutiveErrors = 0
                        restartListening()
                    } else {
                        giveUp()
                    }
                } else {
                    // a busy or confused service usually recovers with a new connection
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || consecutiveErrors > 1) createRecognizer()
                    restartListening(RESTART_DELAY_MS * 4 * consecutiveErrors)
                }
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    /** Switch to the system's default recognition service, once per session, unless that is the one that just failed. */
    private fun fallBackToSystemRecognizer(): Boolean {
        if (triedSystemFallback) return false
        triedSystemFallback = true
        val system = DictationEngine.resolve(ime, DictationEngine.SYSTEM)
        if (!DictationEngine.usable(ime, system) || system == component) return false // none, or the one that failed
        component = system
        engine = DictationEngine.SYSTEM
        preferOffline = false
        if (!createRecognizer()) return false
        toast(R.string.dictation_fallback_system)
        return true
    }

    /** No recognizer works: end the session, then use the voice input method, else say so. */
    private fun giveUp() {
        cancel()
        if (!switchToVoiceIme()) toast(R.string.dictation_unavailable)
    }

    private fun switchToVoiceIme(): Boolean {
        val richImm = RichInputMethodManager.getInstance()
        if (!richImm.isShortcutImeReady) return false
        toast(R.string.dictation_fallback_voice_ime)
        richImm.switchToShortcutIme(ime)
        return true
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    // ---- editing ----

    private fun commandsApply() = commandsEnabled && locale().language == "en"

    private fun locale() = RichInputMethodManager.getInstance().currentSubtypeLocale

    private fun autoCap(): Boolean {
        val settings = Settings.getValues()
        val capFlags = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        return settings.mAutoCap && (settings.mInputAttributes.mInputType and capFlags) != 0
    }

    private fun autoSpace() = Settings.getValues().mInputAttributes.mShouldInsertSpacesAutomatically

    private fun textBefore(): String = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString() ?: ""

    private fun format(raw: String, partial: Boolean): Pair<ParsedUtterance, FormattedText> {
        val before = contextBefore ?: textBefore().also { contextBefore = it }
        val parsed = DictationCommandParser.parse(raw, commandsApply(), partial)
        return parsed to DictationFormatter.format(before, parsed.text, autoCap(), autoSpace())
    }

    private fun showPartial(raw: String) {
        val (_, formatted) = format(raw, partial = true)
        if (formatted.text == composing) return
        connection.beginBatchEdit()
        connection.setComposingText(formatted.text, 1)
        connection.endBatchEdit()
        composing = formatted.text
    }

    /** Keep the composing text as it is. */
    private fun finishComposing() {
        if (composing.isNotEmpty()) {
            connection.finishComposingText()
            history.addLast(Inserted(composing, ""))
        }
        composing = ""
        contextBefore = null
    }

    private fun commitFinal(raw: String) {
        val (parsed, formatted) = format(raw, partial = false)
        connection.beginBatchEdit()
        if (formatted.deleteBefore > 0 || formatted.text.isEmpty()) {
            if (composing.isNotEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
            }
        }
        var removed = ""
        if (formatted.deleteBefore > 0) {
            val before = textBefore()
            removed = before.takeLast(formatted.deleteBefore)
            connection.deleteTextBeforeCursor(formatted.deleteBefore)
        }
        if (formatted.text.isNotEmpty()) {
            connection.commitText(formatted.text, 1)
            history.addLast(Inserted(formatted.text, removed))
        }
        connection.endBatchEdit()
        composing = ""
        contextBefore = null
        parsed.command?.let { runCommand(it) }
        ime.updateShiftStateAfterDictation()
    }

    private fun runCommand(command: DictationCommand) {
        when (command) {
            DictationCommand.DELETE_LAST -> {
                val index = history.indexOfLast { it is Inserted }
                if (index < 0 || index != history.lastIndex || !removeInserted(history[index] as Inserted)) {
                    toast(R.string.dictation_nothing_to_delete)
                    return
                }
                val edit = history.removeAt(index)
                history.addLast(Deleted(edit.text, (edit as Inserted).removedBefore))
            }
            DictationCommand.UNDO -> {
                when (val edit = history.removeLastOrNull()) {
                    is Inserted -> if (!removeInserted(edit)) toast(R.string.dictation_nothing_to_delete)
                    is Deleted -> {
                        connection.beginBatchEdit()
                        connection.commitText(edit.removedBefore + edit.text, 1)
                        connection.endBatchEdit()
                        history.addLast(Inserted(edit.text, edit.removedBefore))
                    }
                    null -> sendCode(KeyCode.UNDO)
                }
            }
            DictationCommand.SEND -> {
                performSend()
            }
            DictationCommand.STOP_LISTENING -> stop(StopReason.COMMAND)
        }
    }

    /** Remove dictated text if it's still right before the cursor, and restore what it replaced. */
    private fun removeInserted(edit: Inserted): Boolean {
        val before = connection.getTextBeforeCursor(edit.text.length, 0)?.toString()
        if (before != edit.text) return false
        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(edit.text.length)
        if (edit.removedBefore.isNotEmpty()) connection.commitText(edit.removedBefore, 1)
        connection.endBatchEdit()
        return true
    }

    /** Perform the editor's action (send, go, search...), or press enter if it has none. */
    private fun performSend() {
        val editorInfo = ime.currentInputEditorInfo
        val action = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        when {
            editorInfo?.actionLabel != null && editorInfo.actionId != 0 -> connection.performEditorAction(editorInfo.actionId)
            action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE -> connection.performEditorAction(action)
            else -> sendCode(Constants.CODE_ENTER)
        }
        tolerateSelectionChangesUntil = SystemClock.uptimeMillis() + TOLERATE_SELECTION_CHANGES_MS
    }

    private fun sendCode(code: Int) {
        isDispatchingKey = true
        try {
            ime.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        } finally {
            isDispatchingKey = false
        }
        tolerateSelectionChangesUntil = SystemClock.uptimeMillis() + TOLERATE_SELECTION_CHANGES_MS
    }

    // ---- UI ----

    private fun showIndicator() {
        val strip = ime.suggestionStripViewForDictation ?: return
        val view = indicator ?: DictationIndicatorView(strip.context).also { indicator = it }
        view.onTap = { toggle() }
        view.onLongPress = { showEnginePicker() }
        updateIndicatorEngine()
        view.setState(DictationIndicatorView.State.STARTING)
        strip.showDictationView(view)
    }

    private fun updateIndicatorEngine() {
        indicator?.engineLabel = when {
            component?.packageName == DictationEngine.GOOGLE_PACKAGE -> ime.getString(R.string.dictation_engine_google)
            else -> ime.getString(R.string.dictation_engine_system)
        }
    }

    private fun hideIndicator() {
        val view = indicator ?: return
        ime.suggestionStripViewForDictation?.hideDictationView(view)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        indicator = null
    }

    private fun toast(res: Int) = KeyboardSwitcher.getInstance().showToast(ime.getString(res), true)

    companion object {
        private const val TAG = "DictationController"
        private const val CONTEXT_LENGTH = 64
        private const val RESTART_DELAY_MS = 50L
        private const val FINISH_TIMEOUT_MS = 2000L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 3
        private const val TOLERATE_SELECTION_CHANGES_MS = 1500L
        private const val EXTRA_DICTATION_MODE = "android.speech.extra.DICTATION_MODE"
        // SpeechRecognizer constants added after minSdk
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
