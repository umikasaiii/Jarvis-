package com.simone.jarvismobile.tts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where the seven bundled Supertonic files live once extracted, or why they don't. */
sealed interface SupertonicBundle {
    data class Ready(val dir: File) : SupertonicBundle
    /** Nothing under [SupertonicAssetFiles.ASSET_DIR] in this APK — expected until the model is added. */
    data object NotBundled : SupertonicBundle
    data class Incomplete(val missing: List<String>) : SupertonicBundle
    data class Failed(val reason: String) : SupertonicBundle
}

/**
 * Copies the Supertonic model out of the APK's `assets/` (compressed, read-only,
 * not addressable by a plain filesystem path) into app-private storage the
 * native sherpa-onnx JNI layer can open directly.
 *
 * Every other neural voice in this app (Kokoro, Piper) is user-imported and
 * never bundled — see [TtsAssetStore]'s own doc comment. Supertonic is the
 * deliberate exception, ships inside the APK, and is provisioned automatically
 * with no user action, which is exactly why this class exists instead of
 * reusing [TtsAssetStore]: that store's whole design is "the user picked this
 * file", which does not apply here.
 *
 * Idempotent: a file already extracted at the expected size is left alone, so
 * this can be called before every [SupertonicTtsEngine.load] at near-zero cost
 * once the first extraction has happened.
 */
@Singleton
class SupertonicAssetProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun targetDir(): File = File(context.filesDir, "tts/supertonic").apply { mkdirs() }

    /** Extracts (or verifies) the bundle; never throws. */
    suspend fun ensureExtracted(): SupertonicBundle = withContext(Dispatchers.IO) {
        val assetNames = runCatching {
            context.assets.list(SupertonicAssetFiles.ASSET_DIR)?.toSet()
        }.getOrNull().orEmpty()

        if (assetNames.isEmpty() || SupertonicAssetFiles.ALL.none { it in assetNames }) {
            return@withContext SupertonicBundle.NotBundled
        }
        val missingFromApk = SupertonicAssetFiles.ALL.filter { it !in assetNames }
        if (missingFromApk.isNotEmpty()) {
            Log.w(TAG, "supertonic_bundle_partial missing=${missingFromApk.size}")
            return@withContext SupertonicBundle.Incomplete(missingFromApk)
        }

        val dir = targetDir()
        for (name in SupertonicAssetFiles.ALL) {
            val target = File(dir, name)
            val assetPath = "${SupertonicAssetFiles.ASSET_DIR}/$name"
            val expectedSize = runCatching {
                context.assets.openFd(assetPath).use { it.length }
            }.getOrDefault(-1L)
            // -1 (compressed asset, no direct fd length) falls through to a
            // straight existence check rather than a size comparison.
            if (target.isFile && (expectedSize < 0 || target.length() == expectedSize)) continue
            val copied = runCatching {
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
                }
                true
            }.getOrElse { e ->
                Log.w(TAG, "supertonic_extract_failed file=$name ${e.javaClass.simpleName}")
                runCatching { target.delete() }
                false
            }
            if (!copied) return@withContext SupertonicBundle.Failed("estrazione_fallita:$name")
        }

        val stillMissing = SupertonicAssetFiles.ALL.filter { !File(dir, it).isFile }
        if (stillMissing.isNotEmpty()) return@withContext SupertonicBundle.Incomplete(stillMissing)
        SupertonicBundle.Ready(dir)
    }

    /** True when the APK actually carries a (complete) Supertonic bundle, without extracting it. */
    fun isBundlePresent(): Boolean = runCatching {
        val names = context.assets.list(SupertonicAssetFiles.ASSET_DIR)?.toSet().orEmpty()
        SupertonicAssetFiles.ALL.all { it in names }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "JarvisSupertonicAssets"
    }
}
