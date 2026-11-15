package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.dictation.DictationIndicatorView
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SplitHubView
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeliPr1ReviewProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun load(editor: EditorInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.normal" }) {
        Settings.getInstance().loadSettings(context, Locale.ENGLISH, InputAttributes(editor, false, context.packageName))
    }

    @Test fun migrationPreservesEveryOldEntryOrderAndExplicitOptOut() {
        val prefs = context.getSharedPreferences("review-migration", Context.MODE_PRIVATE)
        val prefKeys = listOf(Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS)
        for (pref in prefKeys) {
            for (old in listOf(
                ToolbarKey.entries.filterNot { it == ToolbarKey.HIDE_KEYBOARD }.reversed().mapIndexed { i, key -> key.name + Separators.KV + (i % 3 == 0) }.joinToString(Separators.ENTRY),
                listOf("PASTE", "UNDO", "VOICE", "REDO").mapIndexed { i, key -> key + Separators.KV + (i % 2 == 0) }.joinToString(Separators.ENTRY)
            )) {
                prefs.edit { clear(); putString(pref, old) }
                upgradeToolbarPrefs(prefs)
                val upgraded = prefs.getString(pref, null)!!
                assertTrue(upgraded.startsWith(old + Separators.ENTRY), "$pref lost or reordered old entries")
                val state = upgraded.split(Separators.ENTRY).first { it.startsWith("HIDE_KEYBOARD" + Separators.KV) }
                assertEquals("HIDE_KEYBOARD" + Separators.KV + (pref == Settings.PREF_PINNED_TOOLBAR_KEYS), state)
                upgradeToolbarPrefs(prefs)
                assertEquals(upgraded, prefs.getString(pref, null))
                val optOut = upgraded.replace("HIDE_KEYBOARD" + Separators.KV + "true", "HIDE_KEYBOARD" + Separators.KV + "false")
                prefs.edit { putString(pref, optOut) }
                upgradeToolbarPrefs(prefs)
                assertEquals(optOut, prefs.getString(pref, null))
            }
            for (old in listOf("", "obsolete${Separators.KV}true", "UNDO", "UNDO${Separators.KV}nonsense")) {
                prefs.edit { clear(); putString(pref, old) }
                upgradeToolbarPrefs(prefs)
            }
        }
    }

    @Test fun noMicFlagUpdatesAllThreeSurfacesAndNormalAppRestoresVoice() {
        context.prefs().edit { putBoolean(Settings.PREF_DICTATION, true); putString(Settings.PREF_PINNED_TOOLBAR_KEYS, "VOICE${Separators.KV}true") }
        RichInputMethodManager::class.java.getDeclaredField("isDictationAvailable").apply { isAccessible = true }.setBoolean(RichInputMethodManager.getInstance(), true)
        load()
        val themed = ContextThemeWrapper(context, KeyboardTheme.getKeyboardTheme(context).mStyleId)
        helium314.keyboard.keyboard.internal.KeyboardIconsSet.instance.loadIcons(themed)
        val strip = SuggestionStripView(themed, null)
        val hub = SplitHubView(themed)
        strip.hub = hub
        hub.attach(strip, null)
        val hubVoice = hub.findViewWithTag<View>(ToolbarKey.VOICE)
        val toolbarVoice = strip.findViewById<View>(R.id.toolbar).findViewWithTag<View>(ToolbarKey.VOICE)
        val pinnedVoice = strip.findViewById<View>(R.id.pinned_keys).findViewWithTag<View>(ToolbarKey.VOICE)
        for (blocked in listOf(false, true, false, true, false)) {
            val editor = EditorInfo().apply {
                packageName = if (blocked) "test.herdr" else "test.normal"
                inputType = InputType.TYPE_CLASS_TEXT
                privateImeOptions = if (blocked) context.packageName + ".noMicrophoneKey" else null
            }
            load(editor)
            strip.updateVoiceKey()
            assertEquals(!blocked, Settings.getValues().mShowsVoiceInputKey)
            for (view in listOf(hubVoice, toolbarVoice, pinnedVoice)) assertEquals(if (blocked) View.GONE else View.VISIBLE, view.visibility)
        }
        assertEquals(KeyCode.VOICE_INPUT, getCodeForToolbarKey(ToolbarKey.VOICE))
        assertEquals(KeyCode.DICTATION_ENGINE, getCodeForToolbarKeyLongClick(ToolbarKey.VOICE))
        assertEquals(KeyCode.IME_HIDE_UI, getCodeForToolbarKey(ToolbarKey.HIDE_KEYBOARD))
    }

    @Test fun indicatorBackgroundMustStillCoverTheUnderlyingSuggestions() {
        val bad = mutableListOf<String>()
        val directory = File("/home/wolf/herdr-android/reviews/heli-pr1-render-probes").apply { mkdirs() }
        for (style in listOf(KeyboardTheme.STYLE_ROUNDED, KeyboardTheme.STYLE_MATERIAL, KeyboardTheme.STYLE_HOLO)) {
            for (theme in listOf(KeyboardTheme.THEME_LIGHT, KeyboardTheme.THEME_DARK)) {
                for (borders in listOf(true, false)) {
                    context.prefs().edit {
                        putString(Settings.PREF_THEME_STYLE, style)
                        putString(Settings.PREF_THEME_COLORS, theme)
                        putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
                        putBoolean(Settings.PREF_THEME_KEY_BORDERS, borders)
                    }
                    load()
                    val indicator = DictationIndicatorView(context)
                    indicator.layout(0, 0, 600, 100)
                    val bitmap = Bitmap.createBitmap(600, 100, Bitmap.Config.ARGB_8888)
                    // Draw just its background, so status text/animations cannot affect alpha measurements.
                    indicator.background.bounds = android.graphics.Rect(0, 0, 600, 100)
                    indicator.background.draw(Canvas(bitmap))
                    val alpha = Color.alpha(bitmap.getPixel(300, 50))
                    val id = "$style-$theme-borders-$borders"
                    println("BACKGROUND $id center-alpha=$alpha corner-alpha=${Color.alpha(bitmap.getPixel(0, 0))}")
                    File(directory, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (alpha != 255) bad += "$id=$alpha"
                    if (borders) assertEquals(if (style == KeyboardTheme.STYLE_ROUNDED) 0 else 255, Color.alpha(bitmap.getPixel(0, 0)))
                }
            }
        }
        assertTrue(bad.isEmpty(), "Dictation overlays existing suggestions/toolbar; it needs an opaque body. Transparent cases: $bad")
    }
}
