package helium314.keyboard.latin

import android.content.Context
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SplitHubView
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.suggestions.TerminalKeysView
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class HeliPr2TerminalMicProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun load(type: Int, blocked: Boolean, otherKeyboard: Boolean = false) {
        context.prefs().edit {
            putString(Settings.PREF_TERMINAL_APPS, "dev.wolf.herdrgpui,com.termux")
            putBoolean(Settings.PREF_DICTATION, true)
        }
        val editor = EditorInfo().apply {
            packageName = if (blocked) "dev.wolf.herdrgpui" else "com.termux"
            inputType = type
            privateImeOptions = when {
                otherKeyboard -> "other.keyboard.noMicrophoneKey"
                blocked -> context.packageName + ".noMicrophoneKey"
                else -> null
            }
        }
        Settings.getInstance().loadSettings(context, Locale.ENGLISH, InputAttributes(editor, false, context.packageName))
    }

    private fun themed(): Context = ContextThemeWrapper(context, KeyboardTheme.getKeyboardTheme(context).mStyleId).also {
        KeyboardIconsSet.instance.loadIcons(it)
    }

    private fun allVoice(root: View): List<View> = buildList {
        if (root.tag == ToolbarKey.VOICE) add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(allVoice(root.getChildAt(i)))
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 180)
    }

    @Test fun creationHonorsTypeNullAndTextRequestsAndOtherKeyboardDoesNotCount() {
        for (type in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            for (blocked in listOf(true, false)) {
                load(type, blocked)
                for (rows in listOf(TerminalKeysView.STRIP, TerminalKeysView.HUB_BLOCK)) {
                    val terminal = TerminalKeysView(themed(), rows, 4)
                    assertEquals(1, allVoice(terminal).size)
                    assertEquals(if (blocked) View.GONE else View.VISIBLE, allVoice(terminal).single().visibility)
                }
            }
            load(type, true, otherKeyboard = true)
            val terminal = TerminalKeysView(themed(), TerminalKeysView.STRIP, 4)
            assertEquals(View.VISIBLE, allVoice(terminal).single().visibility)
        }
    }

    @Test fun fieldSwitchUpdatesStripAndHubAndReclaimsMicWidth() {
        load(InputType.TYPE_NULL, false)
        helium314.keyboard.accessibility.AccessibilityUtils.init(context)
        val themed = themed()
        val strip = SuggestionStripView(themed, null)
        val hub = SplitHubView(themed)
        strip.hub = hub
        hub.attach(strip, null)
        for (blocked in listOf(false, true, false, true, false)) {
            load(InputType.TYPE_NULL, blocked)
            strip.updateVoiceKey()
            // onStartInputViewInternal calls setNeutralSuggestionStrip -> setSuggestions;
            // both suggestion updates and toolbar mode switches refresh terminal mode.
            strip.setSuggestions(SuggestedWords.getEmptyInstance(), false)
            val stripVoices = allVoice(strip)
            assertEquals(2, stripVoices.size) // toolbar + terminal row (nothing pinned by default)
            val rowMic = stripVoices.last()
            assertEquals(if (blocked) View.GONE else View.VISIBLE, rowMic.visibility)
            assertEquals(View.GONE, stripVoices.first().visibility) // TYPE_NULL never uses the toolbar's mic
            assertEquals(2, allVoice(hub).size) // normal block + terminal block
            val hubMic = allVoice(hub).last()
            assertEquals(if (blocked) View.GONE else View.VISIBLE, hubMic.visibility)
            assertEquals(View.GONE, allVoice(hub).first().visibility)
        }
        load(InputType.TYPE_NULL, false)
        val terminal = TerminalKeysView(themed, TerminalKeysView.HUB_BLOCK, 0)
        val bottom = terminal.getChildAt(1) as LinearLayout
        layout(terminal)
        assertEquals(300, bottom.getChildAt(0).width)
        terminal.setVoiceKeyVisible(false)
        layout(terminal)
        assertEquals(600, bottom.getChildAt(0).width)
        terminal.setVoiceKeyVisible(true)
        layout(terminal)
        assertEquals(300, bottom.getChildAt(0).width)
    }
}
