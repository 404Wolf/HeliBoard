// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-device speech model (Vosk, Apache-2.0), shipped in the APK's assets under [ASSET_DIR]
 * (the nix build puts vosk-model-small-en-us there). On first use it is copied to the app's files
 * (Vosk reads plain files) and loaded once per process, off the main thread; later mic taps reuse
 * the loaded model, so they start right away.
 */
object VoskModel {
    const val ASSET_DIR = "vosk-model"
    private const val TAG = "VoskModel"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var model: Model? = null
    private val waiting = ArrayList<(Model?) -> Unit>()
    private var loading = false

    /** Whether this APK has the model. */
    fun available(context: Context): Boolean = try {
        context.assets.list(ASSET_DIR)?.isNotEmpty() == true
    } catch (e: Exception) {
        false
    }

    /** Start loading in the background (e.g. when the keyboard opens), so the mic starts at once. */
    fun preload(context: Context) {
        if (model == null && available(context)) load(context) {}
    }

    /** The model, loaded if needed; [onLoaded] runs on the main thread (null: no model / failed). */
    fun load(context: Context, onLoaded: (Model?) -> Unit) {
        model?.let { onLoaded(it); return }
        waiting.add(onLoaded)
        if (loading) return
        loading = true
        val appContext = context.applicationContext ?: context
        executor.execute {
            val loaded = try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(unpack(appContext).absolutePath)
            } catch (e: Throwable) {
                Log.w(TAG, "can't load the speech model", e)
                null
            }
            main.post {
                model = loaded
                loading = false
                val callbacks = waiting.toList()
                waiting.clear()
                callbacks.forEach { it(loaded) }
            }
        }
    }

    /** Copy the model from the assets to the files dir, again whenever the APK was updated. */
    private fun unpack(context: Context): File {
        val target = File(context.filesDir, ASSET_DIR)
        val stamp = File(target, ".unpacked")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return target
        target.deleteRecursively()
        copyAssets(context, ASSET_DIR, target)
        stamp.writeText(version)
        return target
    }

    private fun copyAssets(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) { // a file
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssets(context, "$path/$child", File(target, child))
    }
}
