package helium314.keyboard.latin

import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.DefaultColors
import helium314.keyboard.latin.dictation.DictationIndicatorView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HeliIndicatorContrastProbeTest {
    @Test fun transparentThemesChooseReadableBlackOrWhiteForLighterCustomText() {
        val text = 0xffb0b0b0.toInt()
        val fill = DictationIndicatorView.fill(null, Color.TRANSPARENT, text)
        val actual = ColorUtils.calculateContrast(text, fill)
        val black = ColorUtils.calculateContrast(text, Color.BLACK)
        val white = ColorUtils.calculateContrast(text, Color.WHITE)
        println("text=#b0b0b0 fill=${fill.toUInt().toString(16)} contrast=$actual black=$black white=$white")
        assertTrue(actual >= 4.5, "The existing light gray text reads on black ($black), but the new fallback chose white ($white)")
    }

    @Test fun transparentCustomTextIsScoredAtTheAlphaActuallyUsedByTheView() {
        val rawText = 0x2000c000
        val colors = DefaultColors(
            themeStyle = KeyboardTheme.STYLE_ROUNDED, hasKeyBorders = false,
            accent = Color.WHITE, background = Color.TRANSPARENT, keyBackground = Color.GRAY,
            functionalKey = Color.GRAY, spaceBar = Color.GRAY, keyText = rawText, keyHintText = rawText,
        )
        val view = DictationIndicatorView(ApplicationProvider.getApplicationContext<Context>(), colors)
        val paint = DictationIndicatorView::class.java.getDeclaredField("textPaint").apply { isAccessible = true }.get(view) as Paint
        val box = DictationIndicatorView.fill(colors)
        val actual = ColorUtils.calculateContrast(paint.color, box)
        val black = ColorUtils.calculateContrast(paint.color, Color.BLACK)
        println("rawText=${rawText.toUInt().toString(16)} actualPaint=${paint.color.toUInt().toString(16)} fill=${box.toUInt().toString(16)} actualContrast=$actual blackContrast=$black")
        assertTrue(actual >= black, "Score the text as actually drawn: the view makes it opaque, so black has contrast $black rather than $actual")
    }
}
