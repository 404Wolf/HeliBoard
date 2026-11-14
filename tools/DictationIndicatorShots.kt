// SPDX-License-Identifier: GPL-3.0-only
// Screenshot tool for PR review (not part of the PR): renders the dictation indicator over a
// suggestion strip in several keyboard styles and writes PNGs to $HELIBOARD_SHOTS.
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.dictation.DictationIndicatorView
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w412dp-h200dp-xhdpi")
class DictationIndicatorShots {
    private data class Case(val name: String, val style: String, val theme: String, val borders: Boolean)

    @Test fun render() {
        val out = System.getenv("HELIBOARD_SHOTS") ?: return
        val dir = File(out).apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Settings.init(ctx)
        val cases = listOf(
            Case("rounded-light-borders", "Rounded", "light", true),
            Case("rounded-light-noborders", "Rounded", "light", false),
            Case("rounded-dark-borders", "Rounded", "dark", true),
            Case("rounded-dark-noborders", "Rounded", "dark", false),
            Case("material-dark-noborders", "Material", "dark", false),
            Case("holo-dark-borders", "Holo", "dark", true),
        )
        for (c in cases) {
            ctx.prefs().edit(commit = true) {
                putString(Settings.PREF_THEME_STYLE, c.style)
                putString(Settings.PREF_THEME_COLORS, c.theme)
                putString(Settings.PREF_THEME_COLORS_NIGHT, c.theme)
                putBoolean(Settings.PREF_THEME_KEY_BORDERS, c.borders)
            }
            Settings.getInstance().loadSettings(ctx, Locale.US, InputAttributes(EditorInfo(), false, ctx.packageName))
            val colors = Settings.getValues().mColors
            val density = ctx.resources.displayMetrics.density
            val strip = FrameLayout(ctx)
            colors.setBackground(strip, ColorType.MAIN_BACKGROUND)
            // what lies under the indicator in the strip: suggestions and toolbar-like words
            val under = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (w in listOf("hello", "SYSTEM", "world", "⚙ ✂ ⎘ ↶")) under.addView(TextView(ctx).apply {
                text = w; textSize = 16f; setTextColor(colors.get(ColorType.KEY_TEXT)); setPadding((14 * density).toInt(), (12 * density).toInt(), 0, 0)
            })
            strip.addView(under, FrameLayout.LayoutParams(-1, -1))
            val indicator = DictationIndicatorView(ctx)
            indicator.setState(DictationIndicatorView.State.LISTENING)
            indicator.engineLabel = "desktop"
            val m = (4 * density).toInt()
            strip.addView(indicator, FrameLayout.LayoutParams(-1, -1).apply { setMargins(m, m, m, m) })
            val w = (412 * density).toInt(); val h = (48 * density).toInt() + 2 * m
            strip.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            strip.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // the keyboard behind a transparent theme: draw a mid grey so see-through shows
            bitmap.eraseColor(0xff808080.toInt())
            strip.draw(Canvas(bitmap))
            File(dir, "${c.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
