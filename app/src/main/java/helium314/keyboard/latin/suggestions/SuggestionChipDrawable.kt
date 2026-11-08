// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * Background of a suggestion or toolbar button in the chip style: a pill of [height] hugging the
 * text of [textView] (plus [padding] on each side), centred in the view, or a circle when
 * [textView] is null. Nothing is drawn for an empty word slot. The activated state (the word
 * that autocorrect will pick, or a valid typed word) gets an outline in [accent].
 * The fill comes in through [setTintList] like for key backgrounds (pressed = lighter).
 */
class SuggestionChipDrawable(
    private val textView: TextView?,
    private val height: Float,
    private val padding: Float,
    private val accent: Int,
    private val outline: Float,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var tint: ColorStateList? = null
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = minOf(height, b.height().toFloat())
        val w = if (textView == null) h else {
            val text = textView.text
            if (text.isNullOrEmpty()) return
            val textWidth = textView.layout?.let { l -> (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } }
                ?: textView.paint.measureText(text, 0, text.length)
            val maxWidth = (b.width() - kotlin.math.abs(textView.paddingLeft - textView.paddingRight)).toFloat()
            (textWidth * textView.textScaleX + 2 * padding).coerceAtLeast(h * 1.4f).coerceAtMost(maxWidth)
        }
        // centre of the text area: suggestion words may be padded asymmetrically to move the chip
        val cx = if (textView == null) b.exactCenterX()
            else b.left + textView.paddingLeft + (b.width() - textView.paddingLeft - textView.paddingRight) / 2f
        val cy = b.exactCenterY()
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = tint?.getColorForState(state, tint!!.defaultColor) ?: 0xff303030.toInt()
        fill.colorFilter = if (tint == null) filter else null
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        if (textView != null && state.contains(android.R.attr.state_activated)) { // toolbar buttons use activated for toggles
            stroke.color = accent
            stroke.strokeWidth = outline
            rect.inset(outline / 2, outline / 2)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {}

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getPadding(padding: Rect): Boolean {
        padding.setEmpty()
        return false
    }
}
