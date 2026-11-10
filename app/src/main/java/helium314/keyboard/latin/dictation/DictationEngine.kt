// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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

    /** One way to get a [SpeechRecognizer], tried in order by [candidates]. */
    sealed interface Recognizer {
        /** An explicit recognition service. */
        data class Service(val component: ComponentName) : Recognizer
        /** The system's on-device recognizer (Android 13+). */
        data object OnDevice : Recognizer
        /** The service chosen in system settings (voice_recognition_service). */
        data object Default : Recognizer
    }

    companion object {
        const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private val GOOGLE_SERVICE =
            ComponentName(GOOGLE_PACKAGE, "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
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

        private fun exists(context: Context, cn: ComponentName): Boolean = try {
            context.packageManager.getServiceInfo(cn, 0)
            true
        } catch (e: Exception) {
            false
        }

        /**
         * Google's recognition service, if the Google app is installed and provides one. Looked up
         * by name: package visibility filters it out of queryIntentServices.
         */
        fun googleService(context: Context): ComponentName? =
            GOOGLE_SERVICE.takeIf { exists(context, it) }
                ?: recognitionServices(context).firstOrNull { it.packageName == GOOGLE_PACKAGE }

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
            return cn.takeIf { exists(context, it) }
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

        private fun packageInstalled(context: Context, pkg: String): Boolean = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }

        private fun onDeviceAvailable(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 && try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }

        /**
         * Recognizers to try for [engine], best first; the controller moves on to the next one when
         * one can't be created or fails before it ever listened. Only in-keyboard SpeechRecognizer
         * sessions: never a recognition activity or another input method.
         * Google's service is tried even when the package manager doesn't list it (some builds
         * hide it from queries but still bind it); it fails fast if it really isn't there.
         */
        fun candidates(context: Context, engine: DictationEngine): List<Recognizer> {
            val google = (googleService(context) ?: GOOGLE_SERVICE.takeIf { packageInstalled(context, GOOGLE_PACKAGE) })
                ?.let { Recognizer.Service(it) }
            val defaultComponent = defaultService(context)
            val default = Recognizer.Default.takeIf { defaultComponent != null }
            val onDevice = Recognizer.OnDevice.takeIf { onDeviceAvailable(context) }
            return when (engine) {
                // the default is only worth a try when it is not Google's service again
                GOOGLE -> listOfNotNull(google, onDevice, default?.takeIf { defaultComponent != google?.component })
                SYSTEM -> listOfNotNull(default, onDevice, google?.takeIf { it.component != defaultComponent })
            }
        }

        /** Whether [component] (null = the system default) can be used at all. */
        fun usable(context: Context, component: ComponentName?): Boolean =
            component != null || defaultService(context) != null
    }
}
