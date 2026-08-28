package com.simone.jarvismobile.backup

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.backup.BackupEntry
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.core.backup.BackupRef
import com.simone.jarvismobile.core.backup.BackupStatus
import com.simone.jarvismobile.core.backup.EntryKind
import com.simone.jarvismobile.core.backup.Incremental
import com.simone.jarvismobile.core.backup.ManifestCodec
import com.simone.jarvismobile.core.backup.Retention
import com.simone.jarvismobile.core.backup.RetentionPolicy
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.memory.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing backup state (last result, running, error). */
data class BackupState(
    val running: Boolean = false,
    val lastBackupAt: Long = 0L,
    val lastSizeBytes: Long = 0L,
    val lastError: String? = null,
    val count: Int = 0,
)

/**
 * Local-first backup engine (spec). Every evening (scheduled elsewhere) it writes
 * an incremental, AES-256-GCM-encrypted, compressed snapshot of JARVIS's own data
 * — the vault/memory, the Room database and the preferences — plus a plaintext
 * JSON manifest. Heavy re-downloadable assets (AI models, offline maps/Wikipedia)
 * are recorded in the manifest by name/path/size only, never copied. Old backups
 * are pruned by a grandfather-father-son policy. It works fully offline; the cloud
 * is only a later copy, never the source of truth.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: BackupCrypto,
    private val keys: BackupKeyManager,
    private val vault: VaultRepository,
    private val settings: SettingsRepository,
    private val external: ExternalBackupStore,
    private val cloud: CloudSyncManager,
) {
    private val root: File get() = File(context.filesDir, "backups").apply { mkdirs() }

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    init { refreshState() }

    private data class Src(
        val relPath: String,
        val kind: EntryKind,
        val bytes: ByteArray? = null,
        val sourceRef: String = "",
        val size: Long = 0,
    )

    /** Runs a full backup pass; returns the manifest or null on failure. */
    suspend fun runBackup(): BackupManifest? = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(running = true, lastError = null)
        try {
            val previous = latestManifest()
            val prevHashes = previous?.fileHashes().orEmpty()
            val sources = collectSources()

            val id = "backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
            val dir = File(root, id).apply { mkdirs() }

            val entries = ArrayList<BackupEntry>()
            var total = 0L
            val plainZip = File(dir, "payload.zip")
            ZipOutputStream(plainZip.outputStream().buffered()).use { zip ->
                for (s in sources) {
                    if (s.kind == EntryKind.MANIFEST_ONLY) {
                        entries += BackupEntry(
                            name = s.relPath, relPath = s.relPath, sizeBytes = s.size,
                            sha256 = sha256(s.sourceRef.toByteArray() + s.size.toString().toByteArray()),
                            kind = EntryKind.MANIFEST_ONLY, sourceRef = s.sourceRef,
                        )
                        continue
                    }
                    val data = s.bytes ?: continue
                    val hash = sha256(data)
                    total += data.size
                    if (prevHashes[s.relPath] == hash) {
                        // Unchanged: reference the backup that actually holds the bytes.
                        val prevEntry = previous?.entries?.firstOrNull { it.relPath == s.relPath }
                        val storedIn = prevEntry?.storedInBackupId?.ifBlank { previous.id } ?: previous?.id.orEmpty()
                        entries += BackupEntry(s.relPath, s.relPath, data.size.toLong(), hash, storedInBackupId = storedIn)
                    } else {
                        zip.putNextEntry(ZipEntry(s.relPath))
                        zip.write(data)
                        zip.closeEntry()
                        entries += BackupEntry(s.relPath, s.relPath, data.size.toLong(), hash)
                    }
                }
            }

            // Encrypt the payload, then drop the plaintext zip.
            val enc = File(dir, "backup.enc")
            plainZip.inputStream().use { input -> enc.outputStream().use { out -> crypto.encrypt(input, out, keys.contentKey()) } }
            plainZip.delete()

            val manifest = BackupManifest(
                id = id,
                createdAt = System.currentTimeMillis(),
                schemaVersion = ManifestCodec.SCHEMA_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                status = BackupStatus.COMPLETED,
                totalSizeBytes = total,
                archiveSha256 = sha256File(enc),
                entries = entries,
            )
            File(dir, MANIFEST).writeText(ManifestCodec.encode(manifest))

            // Mirror the fresh snapshot to the user's chosen destination folder
            // (if any) so it survives an uninstall and can be restored from there.
            runCatching { external.mirror(dir) }

            applyRetention()
            // Apply the same grandfather-father-son retention to the destination
            // folder, over its OWN contents — never over the internal set, or a
            // first backup after a reinstall would wipe the surviving copies.
            runCatching { pruneExternal() }
            refreshState()
            Log.i(TAG, "backup_done id=$id entries=${entries.size} size=$total")
            manifest
        } catch (t: Throwable) {
            Log.w(TAG, "backup_failed ${t.javaClass.simpleName}")
            _state.value = _state.value.copy(running = false, lastError = t.javaClass.simpleName)
            null
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    suspend fun listBackups(): List<BackupManifest> = withContext(Dispatchers.IO) {
        val internalManifests = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { readManifest(it.name) }.orEmpty()
        // Merge in backups that live only in the destination folder or the cloud
        // (e.g. after a reinstall, when internal storage was wiped, or a restore
        // on a brand new device with nothing local yet), preferring the internal copy.
        val externalManifests = runCatching { external.listManifests() }.getOrDefault(emptyList())
        val cloudManifests = runCatching { cloud.listManifests() }.getOrDefault(emptyList())
        val byId = LinkedHashMap<String, BackupManifest>()
        (internalManifests + externalManifests + cloudManifests).forEach { byId.putIfAbsent(it.id, it) }
        byId.values.sortedByDescending { it.createdAt }
    }

    /** Verifies a backup's encrypted archive against the manifest hash. */
    suspend fun verify(id: String): Boolean = withContext(Dispatchers.IO) {
        if (readManifest(id) == null) runCatching { external.importInto(root, id) }
        if (readManifest(id) == null) runCatching { cloud.importInto(root, id) }
        verifyArchive(id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        runCatching { external.remove(id) }
        runCatching { cloud.remove(id) }
        refreshState()
    }

    /**
     * Restores files from backup [id]. [paths] null = full restore; otherwise only
     * those relPaths (selective). A safety backup of the current state is taken
     * first (spec). Database/preferences are replaced on disk and take effect after
     * the app restarts.
     */
    suspend fun restore(id: String, paths: Set<String>? = null): Boolean = withContext(Dispatchers.IO) {
        // The backup may live only in the destination folder or the cloud (e.g.
        // after a reinstall, or on a brand new device) — pull it into internal
        // storage so the normal path can read it.
        if (readManifest(id) == null) runCatching { external.importInto(root, id) }
        if (readManifest(id) == null) runCatching { cloud.importInto(root, id) }
        val manifest = readManifest(id) ?: return@withContext false
        val wanted = manifest.entries.filter { it.kind == EntryKind.FILE && (paths == null || it.relPath in paths) }

        // Fetch every archive this restore will read BEFORE touching anything.
        // An incremental backup references earlier archives for files that did
        // not change, and those may live only in the destination folder or the
        // cloud.
        val sources = wanted.map { it.storedInBackupId.ifBlank { id } }.toSet()
        for (sourceBackup in sources) {
            if (!File(File(root, sourceBackup), "backup.enc").exists()) {
                runCatching { external.importInto(root, sourceBackup) }
            }
            if (!File(File(root, sourceBackup), "backup.enc").exists()) {
                runCatching { cloud.importInto(root, sourceBackup) }
            }
        }

        // Then check them, still before writing a single byte. Without this a
        // truncated archive would decrypt some entries and fail on others,
        // leaving the app half old and half new and only then reporting failure
        // — the worst possible outcome, because the damage is already done and
        // the user has to know to reach for the safety snapshot. Refusing up
        // front costs one hash per archive and leaves the device untouched.
        val unverifiable = sources.filterNot { verifyArchive(it) }
        if (unverifiable.isNotEmpty()) {
            Log.w(TAG, "restore_refused id=$id unverifiable=${unverifiable.size}")
            return@withContext false
        }

        runBackup() // pre-restore safety snapshot
        var ok = true
        for (entry in wanted) {
            val sourceBackup = entry.storedInBackupId.ifBlank { id }
            val bytes = extract(sourceBackup, entry.relPath)
            if (bytes == null) { ok = false; continue }
            if (!writeTarget(entry.relPath, bytes)) ok = false
        }
        ok
    }

    /**
     * True when [backupId]'s archive is present and matches the SHA-256 its own
     * manifest recorded. Shared by [verify] and [restore] so the check the user
     * can run by hand is exactly the one a restore performs.
     */
    private fun verifyArchive(backupId: String): Boolean {
        val manifest = readManifest(backupId) ?: return false
        val enc = File(File(root, backupId), "backup.enc")
        return enc.exists() && sha256File(enc).equals(manifest.archiveSha256, ignoreCase = true)
    }

    // --- sources ------------------------------------------------------------

    private suspend fun collectSources(): List<Src> {
        val out = ArrayList<Src>()
        // Room database (+ its WAL/SHM sidecars if present).
        for (name in listOf("jarvis.db", "jarvis.db-wal", "jarvis.db-shm")) {
            val f = context.getDatabasePath(name)
            if (f.exists()) out += Src("db/$name", EntryKind.FILE, bytes = f.readBytes())
        }
        // Preferences (DataStore).
        val prefs = File(context.filesDir, "datastore/jarvis_settings.preferences_pb")
        if (prefs.exists()) out += Src("datastore/${prefs.name}", EntryKind.FILE, bytes = prefs.readBytes())
        // Vault notes (memory, agenda, automations…) — the human-readable truth.
        runCatching {
            vault.readAllNotes().forEach { note ->
                out += Src("vault/${note.path}", EntryKind.FILE, bytes = note.content.toByteArray())
            }
        }
        // Heavy, re-downloadable directories → manifest-only (never copied): AI
        // models, offline map/knowledge tiles the user fetched from elsewhere
        // and can fetch again. `documents` used to be lumped in here too, but
        // it holds the user's own imported files and photos ("Archivio
        // locale", § richiesta esplicita dell'utente) — those are NOT
        // re-downloadable, so it is real content below instead.
        for (heavy in listOf("navigation", "models", "knowledge")) {
            val d = File(context.filesDir, heavy)
            if (d.exists()) out += Src(heavy, EntryKind.MANIFEST_ONLY, sourceRef = d.absolutePath, size = dirSize(d))
        }
        // The user's own imported files/photos — real content, same as the
        // vault notes above, restored by writeTarget's generic branch (it
        // writes any relPath straight back under filesDir, which is exactly
        // where DocumentImportManager.PRIVATE_DIR already expects them).
        val documentsDir = File(context.filesDir, "documents")
        if (documentsDir.exists()) {
            documentsDir.walkTopDown().filter { it.isFile }.forEach { f ->
                out += Src("documents/${f.name}", EntryKind.FILE, bytes = f.readBytes())
            }
        }
        return out
    }

    private suspend fun writeTarget(relPath: String, bytes: ByteArray): Boolean = runCatching {
        when {
            relPath.startsWith("db/") -> {
                context.getDatabasePath(relPath.removePrefix("db/")).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                true
            }
            relPath.startsWith("datastore/") -> {
                File(context.filesDir, relPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                true
            }
            relPath.startsWith("vault/") -> {
                // Write back only JARVIS-owned files into the vault; the user's own
                // notes are left untouched (the vault write grant is scoped to
                // JARVIS/ anyway). A read-only or absent vault simply reports false.
                val vaultRel = relPath.removePrefix("vault/")
                if (vaultRel.startsWith("JARVIS/")) {
                    vault.writeJarvisFile(vaultRel.removePrefix("JARVIS/"), bytes.toString(Charsets.UTF_8))
                } else {
                    true // not JARVIS-owned; skipped, not an error
                }
            }
            else -> {
                File(context.filesDir, relPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                true
            }
        }
    }.getOrDefault(false)

    // --- archive helpers ----------------------------------------------------

    private fun extract(backupId: String, relPath: String): ByteArray? {
        val enc = File(File(root, backupId), "backup.enc")
        if (!enc.exists()) return null
        val plain = ByteArrayOutputStream()
        runCatching { enc.inputStream().use { crypto.decrypt(it, plain, keys.contentKey()) } }.onFailure { return null }
        ZipInputStream(ByteArrayInputStream(plain.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == relPath) return zip.readBytes()
            }
        }
        return null
    }

    private fun latestManifest(): BackupManifest? =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { readManifest(it.name) }?.maxByOrNull { it.createdAt }

    private fun readManifest(id: String): BackupManifest? {
        val f = File(File(root, id), MANIFEST)
        if (!f.exists()) return null
        return runCatching { ManifestCodec.decode(f.readText()) }.getOrNull()
    }

    private suspend fun applyRetention() {
        val policy = RetentionPolicy(
            daily = settings.backupRetentionDaily.first(),
            weekly = settings.backupRetentionWeekly.first(),
            monthly = settings.backupRetentionMonthly.first(),
        )
        val refs = (root.listFiles()?.filter { it.isDirectory }?.mapNotNull { readManifest(it.name) }.orEmpty())
            .map { BackupRef(it.id, it.createdAt) }
        Retention.prune(refs, policy).forEach { File(root, it).deleteRecursively() }
    }

    /** Retention over the destination folder's own contents (see runBackup). */
    private suspend fun pruneExternal() {
        if (!external.isConfigured()) return
        val policy = RetentionPolicy(
            daily = settings.backupRetentionDaily.first(),
            weekly = settings.backupRetentionWeekly.first(),
            monthly = settings.backupRetentionMonthly.first(),
        )
        val refs = external.listManifests().map { BackupRef(it.id, it.createdAt) }
        Retention.prune(refs, policy).forEach { external.remove(it) }
    }

    private fun refreshState() {
        val manifests = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { readManifest(it.name) }.orEmpty()
        val latest = manifests.maxByOrNull { it.createdAt }
        _state.value = _state.value.copy(
            lastBackupAt = latest?.createdAt ?: 0L,
            lastSizeBytes = latest?.totalSizeBytes ?: 0L,
            count = manifests.size,
        )
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256File(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }
        return sb.toString()
    }

    private companion object {
        const val TAG = "JarvisBackup"
        const val MANIFEST = "manifest.json"
        const val HEX = "0123456789abcdef"
    }
}
