// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * Key background for the Rounded style with key borders: a rounded rectangle with continuous
 * ("squircle") corners and a soft shade below it, in the key's colour.
 *
 * Replaces the state-list XML drawables, with the same states: pressed, `state_active` for the
 * action key (tinted by its own colour list), `state_empty` keys are only drawn while pressed.
 * Colours come in through [setTintList] (or a colour filter), like for the XML drawables.
 */
class KeyShapeDrawable(
    /** corner radius in px; the curve starts [SMOOTHING] times further from the corner */
    private val radius: Float,
    /** height of the shade below the key in px; 0 for none */
    private val shadeHeight: Float,
    /** whether the shape is a pill (radius = half the height), e.g. for suggestion chips */
    private val pill: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val shadePath = Path()
    private val rect = RectF()
    private var tint: ColorStateList? = null
    private var filter: ColorFilter? = null
    private var alpha = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val bodyBottom = bounds.bottom - shadeHeight
        val r = if (pill) (bodyBottom - bounds.top) / 2f else radius
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bodyBottom)
        smoothRoundRect(path, rect, r)
        if (shadeHeight > 0) {
            rect.offset(0f, shadeHeight)
            smoothRoundRect(shadePath, rect, r)
        } else {
            shadePath.reset()
        }
    }

    override fun draw(canvas: Canvas) {
        val state = state
        val pressed = state.contains(android.R.attr.state_pressed)
        if (state.contains(android.R.attr.state_empty) && !pressed) return
        val color = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        paint.colorFilter = if (tint == null) filter else null
        if (!shadePath.isEmpty && !pressed) { // pressed keys sit flat
            paint.color = ColorUtils.blendARGB(color, Color.BLACK, SHADE_DARKEN)
            paint.alpha = paint.alpha * alpha / 255
            canvas.drawPath(shadePath, paint)
        }
        paint.color = color
        paint.alpha = paint.alpha * alpha / 255
        canvas.drawPath(path, paint)
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

    override fun setTintMode(tintMode: PorterDuff.Mode?) {} // the tint is the colour

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
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

    override fun mutate() = this

    override fun getConstantState(): ConstantState = State(radius, shadeHeight, pill)

    private class State(val radius: Float, val shadeHeight: Float, val pill: Boolean) : ConstantState() {
        override fun newDrawable() = KeyShapeDrawable(radius, shadeHeight, pill)
        override fun getChangingConfigurations() = 0
    }

    companion object {
        /** the corner curve starts this much further from the corner than a circular arc would */
        private const val SMOOTHING = 0.6f
        /** control points this far from the corner, relative to where the curve starts */
        private const val CONTROL = 0.18f
        private const val SHADE_DARKEN = 0.5f

        fun dp(value: Float) = value * Resources.getSystem().displayMetrics.density

        /** Rounded rectangle with continuous corners: one cubic per corner, longer and flatter than a circular arc. */
        fun smoothRoundRect(path: Path, r: RectF, radius: Float) {
            path.reset()
            val a = minOf(radius * (1 + SMOOTHING), r.width() / 2, r.height() / 2)
            val k = a * CONTROL
            path.moveTo(r.left + a, r.top)
            path.lineTo(r.right - a, r.top)
            path.cubicTo(r.right - k, r.top, r.right, r.top + k, r.right, r.top + a)
            path.lineTo(r.right, r.bottom - a)
            path.cubicTo(r.right, r.bottom - k, r.right - k, r.bottom, r.right - a, r.bottom)
            path.lineTo(r.left + a, r.bottom)
            path.cubicTo(r.left + k, r.bottom, r.left, r.bottom - k, r.left, r.bottom - a)
            path.lineTo(r.left, r.top + a)
            path.cubicTo(r.left, r.top + k, r.left + k, r.top, r.left + a, r.top)
            path.close()
        }
    }
}
