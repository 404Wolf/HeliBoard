// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ToolbarHideKeyTest {
    private fun state(pref: String, key: ToolbarKey) =
        pref.split(Separators.ENTRY).first { it.startsWith(key.name + Separators.KV) }.substringAfter(Separators.KV)

    @Test fun hideKeyboardIsPinnedByDefaultAndNothingElseIs() {
        assertEquals("true", state(defaultPinnedToolbarPref, ToolbarKey.HIDE_KEYBOARD))
        val pinned = defaultPinnedToolbarPref.split(Separators.ENTRY).filter { it.endsWith(Separators.KV + "true") }
        assertEquals(listOf(ToolbarKey.HIDE_KEYBOARD.name + Separators.KV + "true"), pinned)
        // In the toolbar itself it is available but off, like the other extra keys.
        assertEquals("false", state(defaultToolbarPref, ToolbarKey.HIDE_KEYBOARD))
    }

    @Test fun anExistingPinnedSetGainsHideKeyboardPinnedOnUpgrade() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("toolbar-upgrade-test", Context.MODE_PRIVATE)
        // a pinned set saved before the key existed, with undo pinned by the user
        val old = defaultPinnedToolbarPref.split(Separators.ENTRY)
            .filterNot { it.startsWith(ToolbarKey.HIDE_KEYBOARD.name + Separators.KV) }
            .joinToString(Separators.ENTRY) { if (it.startsWith("UNDO" + Separators.KV)) "UNDO${Separators.KV}true" else it }
        prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, old); putString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref) }
        upgradeToolbarPrefs(prefs)
        val pinned = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)!!
        assertEquals("true", state(pinned, ToolbarKey.HIDE_KEYBOARD))
        assertEquals("true", state(pinned, ToolbarKey.UNDO))
    }
}
