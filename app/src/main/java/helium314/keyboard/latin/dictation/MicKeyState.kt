// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import java.util.WeakHashMap

/**
 * While dictating, every mic key on screen (toolbar, pinned, terminal row, split hub) turns red
 * and pulses; that is the whole listening indicator, the keyboard layout stays as it is.
 *
 * Dictations recorded while the desktop was away show as a dot on the mic keys instead of a
 * pop-up ([Badge]): a ring while they wait for the desktop, filled once a text is ready to insert
 * (long-press the mic).
 */
object MicKeyState {
    private const val ACTIVE_COLOR = 0xFFE53935.toInt()
    private val pulses = WeakHashMap<View, ValueAnimator>()
    /** Every mic key created, so a badge change reaches them all. */
    private val keys = WeakHashMap<View, GradientDrawable?>()

    enum class Badge { NONE, WAITING, READY }

    var badge = Badge.NONE
        private set

    /** A mic key was created: give it the current badge. */
    fun register(view: View) {
        if (keys.containsKey(view)) return
        keys[view] = null
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeBadge(v) }
        drawBadge(view)
    }

    /** Main thread: what the queue holds changed. */
    fun setBadge(badge: Badge) {
        if (badge == this.badge) return
        this.badge = badge
        for (view in keys.keys.toList()) drawBadge(view)
    }

    private fun drawBadge(view: View) {
        keys[view]?.let { view.overlay.remove(it) }
        keys[view] = null
        if (badge == Badge.NONE) return
        val color = Settings.getValues()?.mColors?.get(ColorType.ACTION_KEY_BACKGROUND) ?: return
        val density = view.resources.displayMetrics.density
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (badge == Badge.READY) setColor(color) else setStroke((1.5f * density).toInt().coerceAtLeast(1), color)
        }
        keys[view] = dot
        view.overlay.add(dot)
        placeBadge(view)
    }

    /** At the top right of the icon, not of the (wider) key. */
    private fun placeBadge(view: View) {
        val dot = keys[view] ?: return
        if (view.width == 0) return
        val density = view.resources.displayMetrics.density
        val size = (7 * density).toInt()
        val icon = ((view as? ImageView)?.drawable?.intrinsicWidth ?: (16 * density).toInt())
            .coerceAtMost(view.width)
        val right = ((view.width + icon) / 2 + size / 3).coerceAtMost(view.width)
        val top = ((view.height - icon) / 2 - size / 3).coerceAtLeast(0)
        dot.setBounds((right - size).coerceAtLeast(0), top, right, top + size)
    }

    /** Mark all mic keys (views tagged [ToolbarKey.VOICE]) below [root]. */
    fun applyTo(root: View?, active: Boolean) {
        if (root == null) return
        if (root.tag == ToolbarKey.VOICE) apply(root, active)
        if (root is ViewGroup) for (i in 0 until root.childCount) applyTo(root.getChildAt(i), active)
    }

    private fun apply(view: View, active: Boolean) {
        pulses.remove(view)?.cancel()
        view.alpha = 1f
        if (view is ImageView) // null: back to the theme's tint
            view.colorFilter = if (active) PorterDuffColorFilter(ACTIVE_COLOR, PorterDuff.Mode.SRC_IN) else null
        if (active) pulses[view] = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.4f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }
}
