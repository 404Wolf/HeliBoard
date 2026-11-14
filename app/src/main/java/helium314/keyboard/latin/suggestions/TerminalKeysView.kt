// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyShapeDrawable
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.dictation.MicKeyState
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey

/**
 * Keys a terminal needs that a text keyboard lacks: Esc, Tab, Ctrl (applies to the next key),
 * arrows, Shift+Enter and a few symbols. Shown instead of suggestions in terminal fields
 * (SettingsValues.mIsTerminal): in the suggestion strip as one row, in the split hub as a grid.
 * A key with a [Key.toolbarKey] is drawn with that toolbar key's icon and acts exactly like it
 * (e.g. the mic: tap dictates, hold picks the dictation engine), so dictation stays one tap away.
 */
class TerminalKeysView(context: Context, rows: List<List<Key>>, keyGap: Int) : LinearLayout(context) {
    class Key(val label: String, val code: Int, val toolbarKey: ToolbarKey? = null)

    var listener: KeyboardActionListener? = null
    private val ctrlKeys = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        val colors = Settings.getValues().mColors
        val res = resources
        val keyColor = colors.get(ColorType.KEY_BACKGROUND)
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(colors.get(ColorType.ACTION_KEY_BACKGROUND), brightenOrDarken(keyColor, true), keyColor)
        )
        for ((r, row) in rows.withIndex()) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((i, key) in row.withIndex()) {
                if (key.toolbarKey != null) {
                    line.addView(iconKey(key.toolbarKey, tint), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i > 0) leftMargin = keyGap
                    })
                    continue
                }
                val view = TextView(context)
                view.text = key.label
                view.gravity = Gravity.CENTER
                view.maxLines = 1
                view.setTextColor(colors.get(ColorType.KEY_TEXT))
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.config_terminal_key_text_size))
                view.includeFontPadding = false
                view.setPadding(0, 0, 0, 0)
                // narrow cells (four across the split hub): shrink "Ctrl" / "Esc" rather than cut them
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    view.setAutoSizeTextTypeUniformWithConfiguration(8, 15, 1, TypedValue.COMPLEX_UNIT_SP)
                view.background = KeyShapeDrawable(
                    res.getDimension(R.dimen.config_key_corner_radius_rounded),
                    res.getDimension(R.dimen.config_key_shade_height_rounded),
                ).also { it.setTintList(tint) }
                view.isClickable = true
                view.setOnClickListener {
                    listener?.onCodeInput(key.code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                    refresh()
                }
                if (key.code == KeyCode.CTRL) ctrlKeys.add(view)
                line.addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = keyGap
                })
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { if (r > 0) topMargin = keyGap })
        }
        setVoiceKeyVisible(!Settings.getValues().mInputAttributes.mNoMicrophoneKeyRequested)
    }

    /**
     * The mic shows in terminal fields although the toolbar's voice key does not (terminal mode
     * hides that), but not where the app asks for no microphone key (it has its own dictation,
     * e.g. Herdr's talk control); its neighbours then take its room.
     */
    fun setVoiceKeyVisible(show: Boolean) {
        for (r in 0 until childCount) {
            val line = getChildAt(r) as LinearLayout
            for (i in 0 until line.childCount) {
                val key = line.getChildAt(i)
                if (key.tag == ToolbarKey.VOICE) key.visibility = if (show) VISIBLE else GONE
            }
        }
    }

    private fun keyBackground(tint: ColorStateList) = KeyShapeDrawable(
        resources.getDimension(R.dimen.config_key_corner_radius_rounded),
        resources.getDimension(R.dimen.config_key_shade_height_rounded),
    ).also { it.setTintList(tint) }

    /** A key showing [toolbarKey]'s icon that does what that toolbar key does (same codes, same feedback). */
    private fun iconKey(toolbarKey: ToolbarKey, tint: ColorStateList): View {
        val view = ImageView(context)
        view.tag = toolbarKey
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = (6 * resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(toolbarKey.name, context))
        if (toolbarKey == ToolbarKey.VOICE) MicKeyState.register(view)
        view.contentDescription = toolbarKey.name.lowercase().getStringResourceOrName("", context)
        Settings.getValues().mColors.setColor(view, ColorType.TOOL_BAR_KEY)
        view.background = keyBackground(tint)
        view.isClickable = true
        view.setOnClickListener {
            onClickToolbarKey(it) { code ->
                listener?.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
            }
            refresh()
        }
        view.setOnLongClickListener {
            onLongClickToolbarKey(it) { code, isRepeat ->
                listener?.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat)
            }
            true
        }
        return view
    }

    /** Gaps between rows and between keys, e.g. to match the keyboard's. */
    fun setGaps(rowGap: Int, keyGap: Int) {
        for (r in 0 until childCount) {
            val line = getChildAt(r) as LinearLayout
            if (r > 0) (line.layoutParams as LayoutParams).topMargin = rowGap
            for (i in 1 until line.childCount) (line.getChildAt(i).layoutParams as LayoutParams).leftMargin = keyGap
        }
        requestLayout()
    }

    /** Show whether Ctrl is waiting for the next key. */
    fun refresh() {
        val active = listener?.isMetaActive(KeyCode.CTRL) == true
        for (key in ctrlKeys) if (key.isActivated != active) key.isActivated = active
    }

    companion object {
        private val ESC = Key("Esc", KeyCode.ESCAPE)
        private val TAB = Key("Tab", KeyCode.TAB)
        private val CTRL = Key("Ctrl", KeyCode.CTRL)
        private val SHIFT_ENTER = Key("⇧⏎", KeyCode.SHIFT_ENTER)
        private val LEFT = Key("←", KeyCode.ARROW_LEFT)
        private val DOWN = Key("↓", KeyCode.ARROW_DOWN)
        private val UP = Key("↑", KeyCode.ARROW_UP)
        private val RIGHT = Key("→", KeyCode.ARROW_RIGHT)
        /** dictation: the toolbar's voice key (mic icon), which terminal mode would otherwise hide */
        private val MIC = Key("", KeyCode.VOICE_INPUT, ToolbarKey.VOICE)
        private fun ch(c: Char) = Key(c.toString(), c.code)

        /** one row, for the suggestion strip */
        val STRIP = listOf(listOf(ESC, TAB, CTRL, LEFT, DOWN, UP, RIGHT, ch('|'), ch('~'), ch('/'), SHIFT_ENTER, MIC))

        /**
         * the split hub's block below its cursor pad: Esc Tab Ctrl on top (about 6 mm each, like
         * the letter keys), Shift+Enter and the mic below (about 9 mm each, the most used)
         */
        val HUB_BLOCK = listOf(listOf(ESC, TAB, CTRL), listOf(SHIFT_ENTER, MIC))

        /** a grid, for the split hub */
        val HUB = listOf(
            listOf(ESC, TAB, CTRL, SHIFT_ENTER),
            listOf(LEFT, DOWN, UP, RIGHT),
            listOf(ch('|'), ch('~'), ch('/'), ch('-')),
        )
    }
}
