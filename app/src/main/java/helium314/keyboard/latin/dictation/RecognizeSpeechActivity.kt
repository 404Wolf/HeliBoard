// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.dictation.desktop.DictationException
import helium314.keyboard.latin.dictation.desktop.PendingClip
import helium314.keyboard.latin.settings.Settings

/**
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] (most apps' mic buttons) on the desktop's Whisper:
 * a small panel at the bottom with the text so far; it ends by itself after a pause or on a tap
 * on it, and is dropped with back or a tap outside.
 *
 * While the desktop is away it records; the app gets [RecognizerIntent.RESULT_NETWORK_ERROR]
 * and the recording waits in the keyboard's queue (the mic key's badge), to be inserted later.
 */
class RecognizeSpeechActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var utterance: WhisperUtterance? = null
    private lateinit var status: TextView
    private lateinit var text: TextView
    private var offlineSince = 0L
    private val tick = object : Runnable {
        override fun run() {
            status.text = getString(R.string.dictation_desktop_offline_for,
                DictationController.duration(SystemClock.uptimeMillis() - offlineSince))
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(panel())
        val uri = DictationEngine.serverUri(this)
        if (uri == null) return end(RecognizerIntent.RESULT_CLIENT_ERROR)
        val caller = callingActivity?.packageName ?: callingPackage ?: "app"
        status.text = intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT) ?: getString(R.string.dictation_listening)
        utterance = WhisperUtterance(this, null, uri, "recognizer/$caller",
            WhisperUtterance.silenceMs(intent.extras), object : WhisperUtterance.Events {
                override fun onReady() {}
                override fun onSpeechStart() {}
                override fun onLevel(dbfs: Float) {}
                override fun onPartial(text: String) {
                    this@RecognizeSpeechActivity.text.text = text
                }
                override fun onOffline() {
                    text.text = ""
                    offlineSince = SystemClock.uptimeMillis()
                    tick.run()
                }
                override fun onFinishing() {
                    if (offlineSince == 0L) status.setText(R.string.dictation_finishing)
                }
                override fun onResult(text: String) {
                    if (text.isEmpty()) return end(RecognizerIntent.RESULT_NO_MATCH)
                    setResult(RESULT_OK, Intent()
                        .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                        .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)))
                    finish()
                }
                override fun onSaved(clip: PendingClip) = end(RecognizerIntent.RESULT_NETWORK_ERROR)
                override fun onError(error: DictationException) = end(when (error.reason()) {
                    DictationException.Reason.AUDIO, DictationException.Reason.PERMISSION -> RecognizerIntent.RESULT_AUDIO_ERROR
                    DictationException.Reason.UNREACHABLE -> RecognizerIntent.RESULT_NETWORK_ERROR
                    else -> RecognizerIntent.RESULT_CLIENT_ERROR
                })
            }).also { it.start() }
    }

    private fun end(result: Int) {
        setResult(result)
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        // Back, or the app went away, without a result: drop it (the result is RESULT_CANCELED).
        utterance?.cancel()
        super.onDestroy()
    }

    /** The theme's colour [attr], before the keyboard (and its colours) has been loaded. */
    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    /** The keyboard's colours; a tap on the panel finishes speaking, outside it cancels. */
    private fun panel(): FrameLayout {
        val colors = Settings.getValues()?.mColors
        val dp = resources.displayMetrics.density
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colors?.get(ColorType.KEY_HINT_TEXT) ?: themeColor(android.R.attr.textColorSecondary))
        }
        text = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minLines = 2
            maxLines = 6
            setTextColor(colors?.get(ColorType.KEY_TEXT) ?: themeColor(android.R.attr.textColorPrimary))
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadii = FloatArray(8) { i -> if (i < 4) 16 * dp else 0f }
                setColor(colors?.get(ColorType.MAIN_BACKGROUND) ?: themeColor(android.R.attr.colorBackground))
            }
            addView(status)
            addView(text)
            isClickable = true
            setOnClickListener { utterance?.stop() }
        }
        return FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }
}
