// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyShapeDrawable
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Compact listening indicator covering the suggestion strip while dictating: a pulsing dot and a
 * scrolling level meter (from the recognizer's RMS dB) in the theme's accent colour, with a status
 * label. Tap to stop, long-press to choose the engine. It is drawn like the keyboard's own keys
 * (their corner radius in the rounded style) in an opaque colour of the keyboard's ([fill]), so
 * it fits the look and nothing under it shows through, in every style and colour scheme.
 */
@SuppressLint("ViewConstructor")
class DictationIndicatorView(
    context: Context,
    colors: Colors = Settings.getValues().mColors,
) : View(context) {
    enum class State { STARTING, LISTENING, LISTENING_OFFLINE, FINISHING }

    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    var engineLabel: String = ""
        set(value) { field = value; invalidate() }

    private var state = State.STARTING
    private val density = resources.displayMetrics.density

    private val accent: Int
    private val textColor: Int
    init {
        accent = colors.get(ColorType.GESTURE_TRAIL) or OPAQUE
        textColor = colors.get(ColorType.KEY_TEXT) or OPAQUE
        // the keys' corner radius in the rounded style, square in the others, as the keys are
        val radius = if (colors.themeStyle == KeyboardTheme.STYLE_ROUNDED)
            resources.getDimension(R.dimen.config_key_corner_radius_rounded) else 0f
        background = KeyShapeDrawable(radius, 0f).also { it.setTint(fill(colors)) }
        contentDescription = context.getString(R.string.dictation_listening)
        isClickable = true
        isLongClickable = true
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 14f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 0x99
        textSize = 11f * density
    }
    private val rect = RectF()

    // level meter: one bar per frame tick, newest on the right
    private val levels = FloatArray(BAR_COUNT)
    private var head = 0
    private var target = 0f
    private var current = 0f
    private var lastShift = 0L

    fun setState(newState: State) {
        state = newState
        invalidate()
    }

    /** @param rmsdB as from RecognitionListener.onRmsChanged, roughly -2 (silence) to 10 (loud) */
    fun setLevel(rmsdB: Float) {
        target = ((rmsdB - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this@DictationIndicatorView, HapticEvent.KEY_PRESS)
            onTap()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this@DictationIndicatorView, HapticEvent.KEY_LONG_PRESS)
            onLongPress()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        // smooth the level: fast attack, slower decay
        current += (target - current) * (if (target > current) 0.5f else 0.15f)
        if (now - lastShift >= BAR_INTERVAL_MS) {
            levels[head] = current
            head = (head + 1) % BAR_COUNT
            lastShift = now
        }

        val h = height.toFloat()
        val w = width.toFloat()
        val cy = h / 2
        val pad = 12 * density

        // pulsing dot: breathes slowly, grows with the voice
        val breathe = (sin(now / 1000.0 * 2 * PI * 0.8) * 0.5 + 0.5).toFloat()
        val baseR = 5 * density
        val dotR = baseR + current * 4 * density
        val cx = pad + 9 * density
        val active = state == State.LISTENING || state == State.LISTENING_OFFLINE
        accentPaint.alpha = if (active) 0xFF else 0x80
        canvas.drawCircle(cx, cy, dotR, accentPaint)
        ringPaint.alpha = ((if (active) 0x90 else 0x40) * (1 - breathe)).toInt()
        canvas.drawCircle(cx, cy, dotR + (3 + 6 * breathe) * density, ringPaint)

        // status label
        val status = context.getString(when (state) {
            State.STARTING -> R.string.dictation_starting
            State.LISTENING -> R.string.dictation_listening
            State.LISTENING_OFFLINE -> R.string.dictation_listening_offline
            State.FINISHING -> R.string.dictation_finishing
        })
        val textX = cx + 18 * density
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2 - (if (engineLabel.isEmpty()) 0f else 5 * density)
        canvas.drawText(status, textX, textY, textPaint)
        if (engineLabel.isNotEmpty())
            canvas.drawText(engineLabel, textX, textY + 14 * density, labelPaint)
        val textEnd = textX + max(textPaint.measureText(status), labelPaint.measureText(engineLabel)) + pad

        // level meter on the right part of the strip
        val barW = 3 * density
        val gap = 2 * density
        val meterW = min(w - textEnd - pad, BAR_COUNT * (barW + gap))
        val bars = (meterW / (barW + gap)).toInt()
        val maxBarH = h * 0.7f
        val minBarH = 2 * density
        var x = w - pad - barW
        for (i in 0 until bars) {
            val level = levels[(head - 1 - i + BAR_COUNT * 2) % BAR_COUNT]
            val barH = minBarH + (maxBarH - minBarH) * level
            accentPaint.alpha = (0xFF * (1f - 0.7f * i / max(bars, 1))).toInt()
            rect.set(x, cy - barH / 2, x + barW, cy + barH / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, accentPaint)
            x -= barW + gap
        }
        accentPaint.alpha = 0xFF
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    companion object {
        /**
         * The box's colour: the functional keys' where keys are drawn (key borders on), else the
         * keyboard's own background, as the keys then have none (their colour is transparent).
         * Made opaque so the strip's words and icons never show through: laid over the keyboard
         * background when that is opaque, else (a transparent theme, a background image) over
         * black or white, whichever gives the key text the higher contrast.
         */
        fun fill(colors: Colors): Int = fill(
            if (colors.hasKeyBorders) colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND) else null,
            colors.get(ColorType.MAIN_BACKGROUND),
            colors.get(ColorType.KEY_TEXT),
        )

        internal fun fill(key: Int?, background: Int, text: Int): Int {
            val wanted = key ?: background
            if (Color.alpha(background) == 0xFF) return ColorUtils.compositeColors(wanted, background)
            return listOf(Color.BLACK, Color.WHITE)
                .map { ColorUtils.compositeColors(wanted, it) }
                .maxBy { contrast(text, it) }
        }

        /** WCAG contrast of [text] (as drawn, over the box) against the opaque [box]. */
        internal fun contrast(text: Int, box: Int): Double =
            ColorUtils.calculateContrast(ColorUtils.compositeColors(text, box), box)

        private const val OPAQUE = -0x1000000
        private const val BAR_COUNT = 48
        private const val BAR_INTERVAL_MS = 50L
        private const val MIN_DB = -2f
        private const val MAX_DB = 10f
    }
}
