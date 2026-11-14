// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyShapeDrawable
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
 * The centre column of a split keyboard (split_hub), in the gap between the halves and aligned
 * to their four key rows. Rows 1-2 are one large key-shaped rectangle, a cursor pad:
 * - drag: move the cursor, horizontally by characters, vertically by lines (arrow keys, so it
 *   works in terminals too)
 * - while typing it shows the suggestions in three lines, best on top; tap one to pick it
 * - hold: paste; double tap (no suggestions): select the word
 * Rows 3-4: undo, clipboard / emoji, voice, two per row so each is about 9 mm wide; in terminal
 * fields Esc, Tab / Ctrl, Shift+Enter (| ~ / - are on the symbols layer).
 * Positioned by InputView ([setRows] gives the key rows' geometry). Suggestions come from the
 * hidden [SuggestionStripView], which also handles taps on the words and buttons.
 */
class SplitHubView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val pad = CursorPad(context)
    private val words = List(3) { TextView(context) } // not shown: carry text and tag, clicks go to the strip
    // rows 3-4: a 2x2 block of buttons (normal) or terminal keys
    private val buttonBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val overlay = FrameLayout(context) // dictation indicator over the pad
    private var terminalBlock: TerminalKeysView? = null
    private var buttons: List<View> = emptyList()
    private var strip: SuggestionStripView? = null
    private var listener: KeyboardActionListener? = null
    private var terminal = false
    private var rowGap = 0
    private var cellGap = 0

    init {
        addView(pad)
        addView(buttonBlock)
        addView(overlay)
        overlay.visibility = GONE
    }

    private fun keyBackground() = KeyShapeDrawable(
        resources.getDimension(R.dimen.config_key_corner_radius_rounded),
        resources.getDimension(R.dimen.config_key_shade_height_rounded),
    ).also { Settings.getValues().mColors.setColor(it, ColorType.FUNCTIONAL_KEY_BACKGROUND) }

    private fun button(key: ToolbarKey): ImageButton {
        val button = createToolbarKey(context, key)
        button.setOnClickListener(strip)
        button.setOnLongClickListener(strip)
        Settings.getValues().mColors.setColor(button, ColorType.TOOL_BAR_KEY)
        button.background = keyBackground()
        return button
    }

    /** Wire up to the strip that computes suggestions and handles clicks. */
    fun attach(strip: SuggestionStripView, listener: KeyboardActionListener?) {
        this.strip = strip
        this.listener = listener
        words.forEach { it.setOnClickListener(strip) }
        pad.background = keyBackground()
        buttons = listOf(ToolbarKey.UNDO, ToolbarKey.CLIPBOARD, ToolbarKey.EMOJI, ToolbarKey.VOICE).map { button(it) }
        fillButtonBlock()
        setVoiceKeyVisible(Settings.getValues().mShowsVoiceInputKey)
        terminalBlock?.let { removeView(it) }
        terminalBlock = TerminalKeysView(context, TerminalKeysView.HUB_BLOCK, cellGap).also { it.listener = listener }
        terminal = !terminal
        setTerminal(!terminal)
    }

    /** Geometry of the key rows: gap between rows and between cells. */
    fun setRows(rowGap: Int, cellGap: Int) {
        if (rowGap == this.rowGap && cellGap == this.cellGap) return
        this.rowGap = rowGap
        this.cellGap = cellGap
        fillButtonBlock()
        terminalBlock?.setGaps(rowGap, cellGap)
        requestLayout()
    }

    /** Undo, clipboard / emoji, voice: two per row, so each is wide enough to hit (about 9 mm). */
    private fun fillButtonBlock() {
        buttonBlock.removeAllViews()
        for (r in 0..1) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..1) {
                val cell = buttons.getOrNull(r * 2 + c) ?: continue
                (cell.parent as? ViewGroup)?.removeView(cell)
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (c > 0) leftMargin = cellGap
                })
            }
            buttonBlock.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (r > 0) topMargin = rowGap
            })
        }
    }

    /**
     * The voice button follows the field like the toolbar's voice key
     * (SettingsValues.mShowsVoiceInputKey): gone where the app asks for no
     * microphone key (privateImeOptions "<package>.noMicrophoneKey", e.g. an
     * app with its own dictation), in a password or email field, or with no
     * voice input. Emoji then takes its row.
     */
    fun setVoiceKeyVisible(show: Boolean) {
        buttons.firstOrNull { it.tag == ToolbarKey.VOICE }?.visibility = if (show) VISIBLE else GONE
    }

    /** Copy the strip's words, the centre (best) one first, to show them on the pad. */
    fun setWords(centre: TextView?, others: List<TextView>) {
        val sources = listOfNotNull(centre) + others
        for ((i, word) in words.withIndex()) {
            val source = sources.getOrNull(i)
            word.text = source?.text
            word.tag = source?.tag
            word.isActivated = source?.isActivated == true
            word.setTextColor(source?.textColors ?: word.textColors)
        }
        pad.invalidate()
    }

    /** Terminal fields: Esc Tab / Ctrl Shift+Enter in place of the buttons; the pad sends arrows. */
    fun setTerminal(terminal: Boolean) {
        if (terminal == this.terminal) return
        this.terminal = terminal
        terminalBlock?.let { if (it.parent != null) removeView(it) }
        if (terminal) terminalBlock?.let { addView(it) }
        buttonBlock.visibility = if (terminal) GONE else VISIBLE
        pad.invalidate()
        requestLayout()
    }

    fun refreshTerminalKeys() {
        terminalBlock?.refresh()
    }

    fun showDictationView(view: View) {
        if (view.parent !== overlay) {
            (view.parent as? ViewGroup)?.removeView(view)
            overlay.addView(view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        view.visibility = VISIBLE
        overlay.visibility = VISIBLE
        overlay.bringToFront()
    }

    fun hideDictationView(view: View) {
        overlay.removeView(view)
        if (overlay.childCount == 0) overlay.visibility = GONE
    }

    // four rows of equal height with rowGap between them
    private fun rowHeight(height: Int) = (height - 3 * rowGap) / 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val twoRows = 2 * rowHeight(height) + rowGap
        val exact = { size: Int -> MeasureSpec.makeMeasureSpec(maxOf(0, size), MeasureSpec.EXACTLY) }
        pad.measure(exact(width), exact(twoRows))
        buttonBlock.measure(exact(width), exact(twoRows))
        overlay.measure(exact(width), exact(twoRows))
        terminalBlock?.takeIf { it.parent === this }?.measure(exact(width), exact(twoRows))
        setMeasuredDimension(width, height)
    }

    // rows 1-2: the cursor pad; rows 3-4: the 2x2 block
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rowHeight = rowHeight(b - t)
        val twoRows = 2 * rowHeight + rowGap
        val blockTop = twoRows + rowGap
        pad.layout(0, 0, width, twoRows)
        overlay.layout(0, 0, width, twoRows)
        buttonBlock.layout(0, blockTop, width, blockTop + twoRows)
        terminalBlock?.takeIf { it.parent === this }?.layout(0, blockTop, width, blockTop + twoRows)
    }

    /** One key-shaped area: drag to move the cursor; shows and picks suggestions; hold to paste. */
    private inner class CursorPad(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private val stepX = 10 * dp
        private val stepY = 24 * dp
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var longPressed = false
        private var lastTapTime = 0L
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                send(KeyCode.CLIPBOARD_PASTE)
            }
        }

        private fun hasWords() = !terminal && words.any { !it.text.isNullOrEmpty() }

        override fun onDraw(canvas: Canvas) {
            val colors = Settings.getValues().mColors
            if (hasWords()) {
                // three lines in the pad's thirds, like the rows they replace
                val lineHeight = height / 3f
                for ((i, word) in words.withIndex()) {
                    val text = word.text
                    if (text.isNullOrEmpty()) continue
                    paint.textSize = resources.getDimension(R.dimen.config_suggestion_text_size)
                    paint.typeface = if (word.isActivated) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    paint.color = word.currentTextColor
                    val shown = TextUtils.ellipsize(text.toString(), android.text.TextPaint(paint), width - 12 * dp, TextUtils.TruncateAt.END)
                    val y = lineHeight * i + lineHeight / 2 - (paint.descent() + paint.ascent()) / 2
                    canvas.drawText(shown, 0, shown.length, width / 2f, y, paint)
                }
            }
            // idle: an empty pad, no hint text (drag still moves the cursor, hold still pastes)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                    dragging = false; longPressed = false
                    isPressed = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                        dragging = true
                        removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed) {
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
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    isPressed = false
                    if (!dragging && !longPressed) onTap(event.y)
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    isPressed = false
                }
            }
            return true
        }

        private fun onTap(y: Float) {
            if (hasWords()) {
                val word = words[(y / (height / 3f)).toInt().coerceIn(0, 2)]
                if (!word.text.isNullOrEmpty()) word.performClick()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (!terminal && now - lastTapTime < ViewConfiguration.getDoubleTapTimeout()) {
                send(KeyCode.CLIPBOARD_SELECT_WORD)
                lastTapTime = 0
            } else {
                lastTapTime = now
            }
        }

        private fun send(code: Int) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener?.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }
}
