// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyShapeDrawable
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey

/**
 * The centre column of a split keyboard (split_hub), in the gap between the halves and aligned
 * to their four key rows, drawn like keys:
 * - rows 1-3: the suggestions (best on top) while typing; when there are none, editing actions
 *   (paste / undo, redo / select word, select all); in terminal fields, terminal keys
 *   (Esc Tab Ctrl Shift+Enter / arrows / | ~ / -)
 * - row 4: clipboard, emoji, voice
 * Positioned by InputView ([setRows] gives the key rows' geometry). Suggestions come from the
 * hidden [SuggestionStripView], which also handles taps on the words and buttons.
 */
class SplitHubView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val rows = List(4) { LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL } }
    private val words = List(3) { TextView(context) }
    private val actionButtons = ArrayList<View>()
    private val overlay = FrameLayout(context) // dictation indicator over rows 1-3
    private var terminalKeys: TerminalKeysView? = null
    private var strip: SuggestionStripView? = null
    private var listener: KeyboardActionListener? = null
    private var mode = Mode.NONE
    private var rowGap = 0
    private var cellGap = 0

    private enum class Mode { NONE, ACTIONS, WORDS, TERMINAL }

    init {
        rows.forEach { addView(it) }
        addView(overlay)
        val colors = Settings.getValues().mColors
        for (word in words) {
            word.gravity = Gravity.CENTER
            word.maxLines = 1
            word.ellipsize = android.text.TextUtils.TruncateAt.END
            word.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.config_suggestion_text_size))
            word.setPadding(cellPadding(), 0, cellPadding(), 0)
            word.background = keyBackground()
        }
        overlay.visibility = GONE
        setColorsOf(colors)
    }

    private fun setColorsOf(colors: helium314.keyboard.latin.common.Colors) {
        words.forEach { it.setTextColor(colors.get(ColorType.KEY_TEXT)) }
    }

    private fun cellPadding() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()

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
        actionButtons.clear()
        actionButtons += listOf(ToolbarKey.PASTE, ToolbarKey.UNDO, ToolbarKey.REDO, ToolbarKey.SELECT_WORD, ToolbarKey.SELECT_ALL)
            .map { button(it) }
        fill(rows[3], listOf(ToolbarKey.CLIPBOARD, ToolbarKey.EMOJI, ToolbarKey.VOICE).map { button(it) })
        terminalKeys = TerminalKeysView(context, TerminalKeysView.HUB, 0).also { it.listener = listener }
        mode = Mode.NONE
        setMode(Mode.ACTIONS)
    }

    /** Geometry of the key rows: gap between rows and between cells. */
    fun setRows(rowGap: Int, cellGap: Int) {
        if (rowGap == this.rowGap && cellGap == this.cellGap) return
        this.rowGap = rowGap
        this.cellGap = cellGap
        rows.forEach { row -> for (i in 1 until row.childCount) (row.getChildAt(i).layoutParams as LinearLayout.LayoutParams).leftMargin = cellGap }
        terminalKeys?.setGaps(rowGap, cellGap)
        requestLayout()
    }

    private fun fill(row: LinearLayout, cells: List<View>) {
        row.removeAllViews()
        for ((i, cell) in cells.withIndex()) {
            (cell.parent as? ViewGroup)?.removeView(cell)
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = cellGap
            })
        }
    }

    private fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        val terminal = terminalKeys
        if (terminal?.parent != null) (terminal.parent as ViewGroup).removeView(terminal)
        when (newMode) {
            Mode.WORDS -> for (i in 0..2) fill(rows[i], listOf(words[i]))
            Mode.ACTIONS -> {
                fill(rows[0], listOf(actionButtons[0]))
                fill(rows[1], listOf(actionButtons[1], actionButtons[2]))
                fill(rows[2], listOf(actionButtons[3], actionButtons[4]))
            }
            Mode.TERMINAL -> {
                for (i in 0..2) rows[i].removeAllViews()
                if (terminal != null) addView(terminal, 0)
            }
            Mode.NONE -> {}
        }
        requestLayout()
    }

    /** Copy the strip's words, the centre (best) one first; no words: editing actions instead. */
    fun setWords(centre: TextView?, others: List<TextView>) {
        if (mode == Mode.TERMINAL) return
        val sources = listOfNotNull(centre) + others
        if (sources.all { it.text.isNullOrEmpty() }) {
            setMode(Mode.ACTIONS)
            return
        }
        for ((i, word) in words.withIndex()) {
            val source = sources.getOrNull(i)
            word.text = source?.text
            word.tag = source?.tag
            word.isEnabled = !source?.text.isNullOrEmpty()
            source?.let { word.setTextColor(it.textColors) }
        }
        setMode(Mode.WORDS)
    }

    /** Terminal fields: terminal keys instead of suggestions and actions. */
    fun setTerminal(terminal: Boolean) {
        if (terminal) setMode(Mode.TERMINAL)
        else if (mode == Mode.TERMINAL) setMode(Mode.ACTIONS)
    }

    fun refreshTerminalKeys() {
        terminalKeys?.refresh()
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

    // four rows of equal height with rowGap between them; terminal keys and the overlay span rows 1-3
    private fun rowHeight(height: Int) = (height - 3 * rowGap) / 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val rowHeight = rowHeight(height)
        val exact = { size: Int -> MeasureSpec.makeMeasureSpec(maxOf(0, size), MeasureSpec.EXACTLY) }
        rows.forEach { it.measure(exact(width), exact(rowHeight)) }
        val topThree = 3 * rowHeight + 2 * rowGap
        overlay.measure(exact(width), exact(topThree))
        terminalKeys?.takeIf { it.parent === this }?.measure(exact(width), exact(topThree))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rowHeight = rowHeight(b - t)
        for ((i, row) in rows.withIndex()) {
            val top = i * (rowHeight + rowGap)
            row.layout(0, top, width, top + rowHeight)
        }
        val topThree = 3 * rowHeight + 2 * rowGap
        overlay.layout(0, 0, width, topThree)
        terminalKeys?.takeIf { it.parent === this }?.layout(0, 0, width, topThree)
    }
}
