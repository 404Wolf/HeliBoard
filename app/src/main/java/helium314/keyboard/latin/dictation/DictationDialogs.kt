// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.dictation.desktop.PendingClip
import helium314.keyboard.latin.utils.getPlatformDialogThemeContext

/**
 * Dialog shown from the keyboard on a long-press of the mic key: dictations the desktop transcribed
 * later (insert one here) and recordings still waiting for it (discard one), then the engines.
 */
fun createEnginePickerDialog(
    context: Context, windowToken: IBinder, onInsert: (PendingClip, String) -> Unit, onChosen: (DictationEngine) -> Unit,
): AlertDialog {
    val selected = DictationEngine.selected(context)
    val googleInstalled = DictationEngine.googleService(context) != null
    val serverSet = DictationEngine.serverUri(context) != null
    val pending = if (serverSet) WhisperRecognizer.pending(context) else null
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    pending?.ready()?.forEach { clip ->
        val text = pending.text(clip.id) ?: return@forEach
        if (text.isNotBlank()) actions += context.getString(R.string.dictation_insert, text.take(40)) to { onInsert(clip, text) }
    }
    pending?.waiting()?.forEach { clip ->
        actions += context.getString(R.string.dictation_discard, DictationController.duration(clip.durationMs)) to { pending.drop(clip.id) }
    }
    val engines = DictationEngine.entries.map {
        when (it) {
            DictationEngine.WHISPER -> context.getString(R.string.dictation_engine_whisper) +
                    if (serverSet) "" else " (" + context.getString(R.string.dictation_engine_no_server) + ")"
            DictationEngine.GOOGLE -> context.getString(R.string.dictation_engine_google) +
                    if (googleInstalled) "" else " (" + context.getString(R.string.dictation_engine_not_installed) + ")"
            DictationEngine.SYSTEM -> context.getString(R.string.dictation_engine_system)
        }
    }
    val items = (actions.map { it.first } + engines).toTypedArray()
    val dialog = AlertDialog.Builder(getPlatformDialogThemeContext(context))
        .setTitle(R.string.dictation_engine)
        .setSingleChoiceItems(items, actions.size + selected.ordinal) { di, i ->
            di.dismiss()
            if (i < actions.size) actions[i].second() else onChosen(DictationEngine.entries[i - actions.size])
        }
        .create()
    val window = dialog.window
    val layoutParams = window?.attributes
    layoutParams?.token = windowToken
    layoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
    window?.attributes = layoutParams
    window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    return dialog
}

/**
 * Invisible activity asking for the microphone permission, which an input method can't do itself.
 * After granting, tap the mic key again.
 */
class DictationPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) R.string.dictation_permission_granted else R.string.dictation_permission_denied, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1

        fun request(context: Context) {
            context.startActivity(Intent(context, DictationPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
    }
}
