package com.simone.jarvismobile.llm

import android.content.Context
import android.os.Build
import com.simone.jarvismobile.core.llm.ModelProfile
import com.simone.jarvismobile.core.llm.ModelProfileFile
import com.simone.jarvismobile.core.llm.ModelProfileRegistry
import com.simone.jarvismobile.core.llm.ModelProfiles
import com.simone.jarvismobile.core.llm.QualificationState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3-A — the persisted registry of model profiles (see [ModelProfiles] for what each qualification level means). One small JSON file in app-private
 * storage, written atomically (temp + rename); it never contains a path, a prompt or any user text, and no operation here touches a model file.
 * Updates come from the real load/inference events of [LitertLmEngine] and from the interrupted-load journal; nothing is inferred from the mere
 * presence of a file.
 */
@Singleton
class ModelProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loadGuard: ModelLoadGuard,
) {
    /** Test seam: instrumented tests point a private instance at a temp file BEFORE first use, so they never touch the real registry. */
    internal var file: File = File(context.filesDir, "model_profiles.json")
    private val lock = Any()
    private val store: ModelProfileFile by lazy { ModelProfileFile(file) }
    private val registry: ModelProfileRegistry by lazy { store.load().registry }

    /** True while the last write failed: the in-memory profiles are the newest and are written again on the next change. Never silent. */
    @Volatile var saveFailing: Boolean = false
        private set

    fun all(): List<ModelProfile> = synchronized(lock) { registry.all() }

    /** Coarse hardware profile of THIS device (model, Android SDK, RAM): what a qualification is valid for. Not a personal identifier. */
    fun deviceKey(totalRamBytes: Long): String = "${Build.MANUFACTURER} ${Build.MODEL}|sdk${Build.VERSION.SDK_INT}|ram${if (totalRamBytes > 0) totalRamBytes / (1024 * 1024) else -1}MB"

    private fun header(f: File): ByteArray = runCatching { FileInputStream(f).use { i -> ByteArray(8).also { i.read(it) } } }.getOrDefault(ByteArray(0))

    private fun stamp(f: File) = ModelProfiles.fingerprint(f.length(), f.lastModified())

    /**
     * The profile of the file as it is NOW. Same name and size is not identity: the stamp (size + last-modified, no hashing) recorded when the profile
     * was observed must still match, otherwise the file was replaced/modified and every proof (and any qualification) is dropped.
     */
    private fun profileFor(f: File): ModelProfile = synchronized(lock) {
        val now = stamp(f)
        val known = registry.findByNameSize(f.name, f.length())
        if (known == null) {
            ModelProfiles.staticCheck(f.name, f.length(), header(f), LITERTLM_VERSION, System.currentTimeMillis()).copy(fingerprint = now).also { registry.put(it) }
        } else {
            val checked = ModelProfiles.checkArtifact(known, now)
            if (checked != known) { registry.rekey(known.id, checked); persist() }
            checked
        }
    }

    /** Whether the stored DEVICE_QUALIFIED still describes the file at [path] right now (stamp + hash + evidence); false when it cannot be verified. */
    fun isQualifiedNow(path: String): Boolean = runCatching {
        val f = File(path)
        synchronized(lock) { registry.findByNameSize(f.name, f.length())?.let { ModelProfiles.qualifiedNow(it, stamp(f)) } ?: false }
    }.getOrDefault(false)

    fun noteLoad(path: String, slot: String, ok: Boolean, error: String?, loadMs: Long, configuredWindow: Int?, pssBefore: Long, pssAfter: Long, totalRam: Long) {
        runCatching {
            val f = File(path)
            if (!f.isFile) return
            synchronized(lock) {
                val p = profileFor(f)
                val upd = ModelProfiles.onLoad(
                    ModelProfiles.revalidate(p, LITERTLM_VERSION, p.sha256), ok, error, System.currentTimeMillis(), loadMs, slot.uppercase(), configuredWindow, "CPU",
                    LITERTLM_VERSION, deviceKey(totalRam), pssBefore.takeIf { it >= 0 }, pssAfter.takeIf { it >= 0 },
                )
                registry.put(upd); persist()
            }
        }
    }

    /** A real generation answered (or failed) for the artifact at [path]. Persists only when the qualification state changes. */
    fun noteInference(path: String?, ok: Boolean, durationMs: Long) {
        if (path == null) return
        runCatching {
            val f = File(path)
            if (!f.isFile) return
            synchronized(lock) {
                val p = profileFor(f)
                val upd = ModelProfiles.onInference(p, ok, System.currentTimeMillis(), durationMs, if (ok) "chat" else null)
                registry.put(upd)
                if (upd.state != p.state || upd.lastInferenceOk != p.lastInferenceOk) persist()
            }
        }
    }

    /** Applies the loads the previous process did not survive (idempotent per attempt). Never acts on a model file. */
    fun syncInterrupted() {
        runCatching {
            for (i in loadGuard.guard.interrupted()) {
                val f = File(i.path)
                if (!f.isFile) continue
                synchronized(lock) {
                    val p = profileFor(f)
                    if (p.lastError == "load_interrupted_process_ended" && (p.lastAttemptAt ?: 0L) >= i.startedAt) return@synchronized
                    registry.put(ModelProfiles.onInterruptedLoad(p, maxOf(System.currentTimeMillis(), i.startedAt))); persist()
                }
            }
        }
    }

    /** On demand only (multi-GB read): computes the SHA-256 and re-keys the profile; the caller runs it off the main thread. */
    fun computeSha256(path: String, onProgress: ((Long) -> Unit)? = null): String? = runCatching {
        val f = File(path)
        val before = stamp(f)
        val hash = FileInputStream(f).use { ModelProfiles.sha256(it, onProgress) }
        if (stamp(f) != before) return@runCatching null // the file changed while it was being read: no identity is claimed
        synchronized(lock) {
            val p = profileFor(f)
            registry.rekey(p.id, ModelProfiles.revalidate(ModelProfiles.withHash(p, hash, before), LITERTLM_VERSION, hash)); persist()
        }
        hash
    }.getOrNull()

    /** Non-destructive write through [ModelProfileFile] (temp + fsync + backup + atomic rename); a failure keeps the previous file and is reported by [saveFailing]. */
    private fun persist() { saveFailing = !store.save(registry) }

    /** Counts per qualification level, for the diagnostics screen. */
    fun summary(): Map<QualificationState, Int> = all().groupingBy { it.state }.eachCount()

    companion object {
        /** Must equal the `litertlm` entry of gradle/libs.versions.toml (pinned by a source guard test). */
        const val LITERTLM_VERSION = "0.14.0"
    }
}
