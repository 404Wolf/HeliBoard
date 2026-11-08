// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/** Speech recognition services the keyboard can dictate with. */
enum class DictationEngine(val prefValue: String) {
    /** Google's recognition service (Google app): streams partial results while you speak. */
    GOOGLE("google"),
    /** Whatever the system's default recognition service is (Settings > voice input). */
    SYSTEM("system");

    companion object {
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val TAG = "DictationEngine"

        fun fromPref(value: String?) = entries.firstOrNull { it.prefValue == value } ?: GOOGLE

        fun selected(context: Context) = fromPref(
            context.prefs().getString(Settings.PREF_DICTATION_ENGINE, Defaults.PREF_DICTATION_ENGINE)
        )

        fun isDictationEnabled(context: Context) =
            context.prefs().getBoolean(Settings.PREF_DICTATION, Defaults.PREF_DICTATION)

        private fun recognitionServices(context: Context): List<ComponentName> = try {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) } }
        } catch (e: Exception) {
            Log.w(TAG, "can't query recognition services", e)
            emptyList()
        }

        /** Google's recognition service, if the Google app is installed and provides one. */
        fun googleService(context: Context): ComponentName? =
            recognitionServices(context).firstOrNull { it.packageName == GOOGLE_PACKAGE }

        /** Whether any recognition service is available, so the mic key can dictate. */
        fun anyAvailable(context: Context): Boolean = try {
            recognitionServices(context).isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }

        /** The service chosen in system settings (voice_recognition_service), if any is set and installed. */
        fun defaultService(context: Context): ComponentName? {
            val flat = try {
                android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            } catch (e: Exception) {
                null
            }
            val cn = flat?.takeIf { it.isNotEmpty() }?.let { ComponentName.unflattenFromString(it) } ?: return null
            return cn.takeIf { it in recognitionServices(context) }
        }

        /**
         * The service for [engine], or null for the system default (Settings > voice input).
         * Google falls back to the system default when the Google app's service is missing.
         * Other installed services are not picked on their own: assistants (e.g. Claude's)
         * register recognition services that are not meant for dictation.
         */
        fun resolve(context: Context, engine: DictationEngine): ComponentName? = when (engine) {
            GOOGLE -> googleService(context)
            SYSTEM -> null
        }

        /** Whether [component] (null = the system default) can be used at all. */
        fun usable(context: Context, component: ComponentName?): Boolean =
            component != null || defaultService(context) != null
    }
}
