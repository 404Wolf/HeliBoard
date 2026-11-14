// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.Color
import helium314.keyboard.latin.dictation.DictationIndicatorView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DictationIndicatorFillTest {
    private val darkBackground = 0xff263238.toInt()
    private val functionalKey = 0xff2d393f.toInt()
    private val lightText = Color.WHITE
    private val darkText = 0xff37474f.toInt()

    @Test fun withKeyBordersTheBoxIsAFunctionalKey() {
        assertEquals(functionalKey, DictationIndicatorView.fill(functionalKey, darkBackground, lightText))
    }

    @Test fun withoutKeyBordersTheBoxIsTheKeyboardBackgroundNeverTransparent() {
        // keys without borders have a transparent colour; the box uses the background instead
        assertEquals(darkBackground, DictationIndicatorView.fill(null, darkBackground, lightText))
    }

    @Test fun aTransparentThemeOrBackgroundImageStillGetsAnOpaqueBox() {
        // Wolf's theme: a transparent keyboard background, light key text
        val box = DictationIndicatorView.fill(null, Color.TRANSPARENT, lightText)
        assertEquals(0xFF, Color.alpha(box))
        assertEquals(Color.BLACK, box)
        // light theme with dark text: white under it
        assertEquals(Color.WHITE, DictationIndicatorView.fill(null, 0x00ffffff, darkText))
        // a translucent custom key colour is laid over the background, so it stays opaque
        val translucentKey = 0x80ff0000.toInt()
        assertEquals(0xFF, Color.alpha(DictationIndicatorView.fill(translucentKey, darkBackground, lightText)))
    }

    @Test fun onATransparentKeyboardTheBoxGivesTheKeyTextTheBetterContrast() {
        // light grey text: black under it reads (9.7:1); white would not (2.2:1)
        assertEquals(Color.BLACK, DictationIndicatorView.fill(null, Color.TRANSPARENT, 0xffb0b0b0.toInt()))
        assertEquals(Color.BLACK, DictationIndicatorView.fill(null, Color.TRANSPARENT, Color.WHITE))
        assertEquals(Color.WHITE, DictationIndicatorView.fill(null, Color.TRANSPARENT, Color.BLACK))
        assertEquals(Color.WHITE, DictationIndicatorView.fill(null, Color.TRANSPARENT, 0xff37474f.toInt()))
        // mid grey: whichever is higher, never below the other
        for (text in listOf(0xff777777.toInt(), 0xff808080.toInt(), 0xff9e9e9e.toInt(), 0xff5c6bc0.toInt())) {
            val box = DictationIndicatorView.fill(null, Color.TRANSPARENT, text)
            val other = if (box == Color.BLACK) Color.WHITE else Color.BLACK
            kotlin.test.assertTrue(DictationIndicatorView.contrast(text, box) >= DictationIndicatorView.contrast(text, other))
        }
        // a translucent text colour is judged as drawn
        assertEquals(Color.BLACK, DictationIndicatorView.fill(null, Color.TRANSPARENT, 0xccb0b0b0.toInt()))
    }
}
