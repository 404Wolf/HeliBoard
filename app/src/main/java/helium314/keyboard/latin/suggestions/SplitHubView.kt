// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import kotlin.math.abs

/**
 * The centre of a split keyboard (split_hub): instead of a suggestion strip above the keys and an
 * empty gap between the halves, the gap holds the suggestions as stacked chips (best on top), a
 * cursor trackpad, and round buttons (clipboard, emoji, voice, settings). Positioned over the
 * split gap by InputView; the suggestions come from the (hidden) [SuggestionStripView], which also
 * handles taps on the chips and buttons, so behaviour is the same as in the strip.
 */
class SplitHubView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val chipArea = FrameLayout(context)
    private val chipColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val chips = List(CHIP_COUNT) { TextView(context, null, R.attr.suggestionWordStyle) }
    private val trackpad = Trackpad(context)
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private var dictationView: View? = null
    private var strip: SuggestionStripView? = null
    var listener: KeyboardActionListener? = null

    init {
        orientation = VERTICAL
        val colors = Settings.getValues().mColors
        val res = resources
        val chipHeight = res.getDimensionPixelSize(R.dimen.config_suggestion_chip_height)
        val gap = res.getDimensionPixelSize(R.dimen.config_split_hub_gap)
        for ((i, chip) in chips.withIndex()) {
            chip.gravity = Gravity.CENTER
            chip.maxLines = 1
            chip.ellipsize = android.text.TextUtils.TruncateAt.END
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.config_suggestion_text_size))
            chip.background = SuggestionChipDrawable(
                chip, chipHeight.toFloat(), res.getDimension(R.dimen.config_suggestion_chip_padding),
                colors.get(ColorType.ACTION_KEY_BACKGROUND), res.getDimension(R.dimen.config_suggestion_chip_outline),
            ).also { colors.setColor(it, ColorType.KEY_BACKGROUND) }
            chipColumn.addView(chip, LayoutParams(LayoutParams.MATCH_PARENT, chipHeight).apply {
                if (i > 0) topMargin = gap / 2
            })
        }
        chipArea.addView(chipColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(chipArea, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        trackpad.colorOutline = colors.get(ColorType.KEY_BACKGROUND)
        trackpad.colorHint = colors.get(ColorType.KEY_HINT_TEXT)
        addView(trackpad, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gap; bottomMargin = gap })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, chipHeight))
    }

    /** Wire up to the strip that computes suggestions and handles clicks. */
    fun attach(strip: SuggestionStripView, listener: KeyboardActionListener?) {
        this.strip = strip
        this.listener = listener
        trackpad.listener = listener
        chips.forEach { it.setOnClickListener(strip) }
        buttons.removeAllViews()
        val colors = Settings.getValues().mColors
        val res = resources
        val size = res.getDimensionPixelSize(R.dimen.config_suggestion_chip_height)
        for (key in listOf(ToolbarKey.CLIPBOARD, ToolbarKey.EMOJI, ToolbarKey.VOICE, ToolbarKey.SETTINGS)) {
            val button = createToolbarKey(context, key)
            button.setOnClickListener(strip)
            button.setOnLongClickListener(strip)
            colors.setColor(button, ColorType.TOOL_BAR_KEY)
            button.background = SuggestionChipDrawable(
                null, size.toFloat(), 0f, colors.get(ColorType.ACTION_KEY_BACKGROUND), 0f,
            ).also { colors.setColor(it, ColorType.KEY_BACKGROUND) }
            buttons.addView(button, LayoutParams(0, size, 1f))
        }
    }

    /** Copy the strip's words: best (centre) first, then the others. */
    fun setWords(centre: TextView?, others: List<TextView>) {
        val words = listOfNotNull(centre) + others
        for ((i, chip) in chips.withIndex()) {
            val word = words.getOrNull(i)
            chip.text = word?.text
            chip.tag = word?.tag
            chip.isActivated = word?.isActivated == true
            chip.isEnabled = !word?.text.isNullOrEmpty()
            word?.let { chip.setTextColor(it.textColors) }
        }
    }

    fun showDictationView(view: View) {
        if (view.parent !== chipArea) {
            (view.parent as? ViewGroup)?.removeView(view)
            chipArea.addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        dictationView = view
        view.visibility = VISIBLE
        view.bringToFront()
    }

    fun hideDictationView(view: View) {
        if (dictationView === view) dictationView = null
        chipArea.removeView(view)
    }

    /** Drag to move the cursor: horizontally by characters, vertically by lines. */
    private class Trackpad(context: Context) : View(context) {
        var listener: KeyboardActionListener? = null
        var colorOutline = 0
        var colorHint = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val stepX = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9f, resources.displayMetrics)
        private val stepY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, resources.displayMetrics)
        private var lastX = 0f
        private var lastY = 0f

        override fun onDraw(canvas: Canvas) {
            val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
            val stroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics)
            rect.set(stroke, stroke, width - stroke, height - stroke)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = colorOutline
            canvas.drawRoundRect(rect, r, r, paint)
            // a small grip in the middle, so it reads as a touch surface
            paint.style = Paint.Style.FILL
            paint.color = colorHint
            paint.alpha = 0x60
            val dot = stroke * 1.4f
            val cx = width / 2f
            val cy = height / 2f
            for (i in -2..2) canvas.drawCircle(cx + i * dot * 5, cy, dot, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    while (abs(event.x - lastX) >= stepX) {
                        val right = event.x > lastX
                        send(if (right) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT)
                        lastX += if (right) stepX else -stepX
                    }
                    while (abs(event.y - lastY) >= stepY) {
                        val down = event.y > lastY
                        send(if (down) KeyCode.ARROW_DOWN else KeyCode.ARROW_UP)
                        lastY += if (down) stepY else -stepY
                    }
                }
            }
            return true
        }

        private fun send(code: Int) {
            listener?.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    companion object {
        private const val CHIP_COUNT = 3
    }
}
