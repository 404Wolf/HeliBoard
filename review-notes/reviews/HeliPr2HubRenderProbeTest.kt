package helium314.keyboard.latin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.dictation.DictationIndicatorView
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SplitHubView
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.prefs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeliPr2HubRenderProbeTest {
    @Test fun renderActualHubOverlay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = File("..").canonicalFile.name.endsWith("-base")
        val directory = File("/home/wolf/herdr-android/reviews/heli-pr2-final-hub-renders").apply { mkdirs() }
        for (theme in listOf(KeyboardTheme.THEME_LIGHT, KeyboardTheme.THEME_DARK)) {
            for (borders in listOf(false, true)) {
                context.prefs().edit {
                    putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_ROUNDED)
                    putString(Settings.PREF_THEME_COLORS, theme)
                    putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
                    putBoolean(Settings.PREF_THEME_KEY_BORDERS, borders)
                }
                Settings.getInstance().loadSettings(context)
                val themed = ContextThemeWrapper(context, KeyboardTheme.getKeyboardTheme(context).mStyleId)
                KeyboardIconsSet.instance.loadIcons(themed)
                val strip = SuggestionStripView(themed, null)
                val hub = SplitHubView(themed)
                hub.attach(strip, null)
                hub.setRows(4, 4)
                val indicator = DictationIndicatorView(context).apply {
                    engineLabel = "SYSTEM"
                    setState(DictationIndicatorView.State.LISTENING)
                }
                hub.showDictationView(indicator)
                hub.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
                hub.layout(0, 0, 220, 280)
                assertSame(hub, (indicator.parent as View).parent)
                val bitmap = Bitmap.createBitmap(220, 280, Bitmap.Config.ARGB_8888)
                hub.draw(Canvas(bitmap))
                File(directory, "${if (base) "base" else "head"}-$theme-borders-$borders.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
