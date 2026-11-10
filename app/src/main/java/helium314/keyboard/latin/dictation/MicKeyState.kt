// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import helium314.keyboard.latin.utils.ToolbarKey
import java.util.WeakHashMap

/**
 * While dictating, every mic key on screen (toolbar, pinned, terminal row, split hub) turns red
 * and pulses; that is the whole listening indicator, the keyboard layout stays as it is.
 */
object MicKeyState {
    private const val ACTIVE_COLOR = 0xFFE53935.toInt()
    private val pulses = WeakHashMap<View, ValueAnimator>()

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
