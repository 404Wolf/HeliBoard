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
}
