// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InputAttributesTest {
    private val ime = "helium314.keyboard"

    private fun attributes(inputType: Int, privateImeOptions: String?) = InputAttributes(
        EditorInfo().apply {
            this.inputType = inputType
            this.privateImeOptions = privateImeOptions
            packageName = "dev.wolf.herdrgpui"
        },
        false,
        ime,
    )

    @Test fun anAppWithItsOwnDictationCanAskForNoMicrophoneKey() {
        // Herdr's composer field: its own talk control, so no keyboard mic.
        val asked = attributes(InputType.TYPE_CLASS_TEXT, "$ime.noMicrophoneKey,noMicrophoneKey,nm")
        assertTrue(asked.mNoMicrophoneKeyRequested)
        assertFalse(asked.mShouldShowVoiceInputKey)
        assertFalse(attributes(InputType.TYPE_CLASS_TEXT, null).mNoMicrophoneKeyRequested)
        // Only this keyboard's own key counts.
        assertFalse(attributes(InputType.TYPE_CLASS_TEXT, "com.google.android.inputmethod.latin.noMicrophoneKey")
            .mNoMicrophoneKeyRequested)
    }

    @Test fun theRequestCountsForTerminalInputToo() {
        // A terminal's own input (TYPE_NULL) skips the text-field attributes, but the terminal
        // keys' mic must still honour the app's request.
        assertTrue(attributes(InputType.TYPE_NULL, "$ime.noMicrophoneKey").mNoMicrophoneKeyRequested)
        assertFalse(attributes(InputType.TYPE_NULL, null).mNoMicrophoneKeyRequested)
    }
}
