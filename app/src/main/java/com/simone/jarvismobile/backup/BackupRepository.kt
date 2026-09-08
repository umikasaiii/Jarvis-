package com.simone.jarvismobile.backup

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.background.JarvisDatabase
import com.simone.jarvismobile.core.backup.BackupCompatibility
import com.simone.jarvismobile.core.backup.BackupCompatibilityClassifier
import com.simone.jarvismobile.core.backup.BackupEntry
import com.simone.jarvismobile.core.backup.BackupId
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.core.backup.BackupPathSafety
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
 * Bounded, privacy-safe evidence of the most recent [BackupRepository.restore]
 * call (§ JARVIS Implementation Master Plan PASSAGGIO 10 §M) — enough to answer
 * "what stage did it reach and why did it stop," never file contents or personal
 * values.
 */
data class RestoreDiagnostic(
    val backupId: String,
    val manifestAccepted: Boolean,
    val compatibility: BackupCompatibility?,
    val checksumsVerified: Boolean,
    val stagingSucceeded: Boolean,
    val cutoverSucceeded: Boolean,
    val recoveryRequired: Boolean,
    val failedAtStage: String? = null,
)

/**
 * Local-first backup engine (spec). Every evening (scheduled elsewhere) it writes
 * an incremental, AES-256-GCM-encrypted, compressed snapshot of JARVIS's own data
 * — the vault/memory, the Room database and the preferences — plus a plaintext
 * JSON manifest. Heavy re-downloadable assets (AI models, offline maps/Wikipedia)
 * are recorded in the manifest by name/path/size only, never copied. Old backups
 * are pruned by a grandfather-father-son policy. It works fully offline; the cloud
 * is only a later copy, never the source of truth.
 *
 * A backup is a verified *snapshot/transport* of the canonical stores below —
 * never a second source of truth. Restore always goes through the same shape
 * (§ JARVIS Implementation Master Plan PASSAGGIO 10): validate the manifest and
 * its declared compatibility, verify every payload's checksum, stage the
 * decrypted bytes in complete isolation from live storage, and only THEN cut
 * over — so a corrupt, incompatible, incomplete or cancelled restore leaves
 * live JARVIS exactly as it was, never half-restored.
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
    private val database: JarvisDatabase,
) {
    private val root: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val stagingRoot: File get() = File(root, "restore_staging")

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _restoreDiagnostic = MutableStateFlow<RestoreDiagnostic?>(null)
    val restoreDiagnostic: StateFlow<RestoreDiagnostic?> = _restoreDiagnostic.asStateFlow()

    init { refreshState() }

    /**
     * Finishes an interrupted cutover (process death between staging and the
     * last file replace — §I: the staging directory's own leftover presence
     * IS the recovery marker, since everything in it already passed its
     * checksum) and clears the derived Weather/Health caches a restored
     * `datastore/` category may have brought back stale (§C/§K). A no-op on
     * every normal start; called once at cold start from `JarvisApplication`,
     * the same "harmless re-arm" pattern already used there for schedulers.
     */
    suspend fun completePendingRestoreRecovery() = withContext(Dispatchers.IO) {
        val staged = RestoreStaging.stagedRelPaths(stagingRoot)
        if (staged.isNotEmpty()) {
            Log.i(TAG, "restore_recovery_resuming entries=${staged.size}")
            var allOk = true
            for (relPath in staged) {
                val bytes = RestoreStaging.readStaged(stagingRoot, relPath) ?: continue
                if (!writeTarget(relPath, bytes)) allOk = false
            }
            if (allOk) {
                RestoreStaging.cleanup(stagingRoot)
                Log.i(TAG, "restore_recovery_completed")
            } else {
                Log.w(TAG, "restore_recovery_incomplete_will_retry")
            }
        }
        clearDerivedCachesIfMarked()
    }

    private suspend fun clearDerivedCachesIfMarked() {
        val marker = File(root, CLEAR_DERIVED_CACHES_MARKER)
        if (!marker.exists()) return
        runCatching {
            settings.setWeatherOutlookCache("")
            settings.setHealthDailyCache("")
        }
        runCatching { marker.delete() }
        Log.i(TAG, "restore_derived_caches_cleared")
    }

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
                dbSchemaVersion = if (sources.any { it.relPath.startsWith("db/") }) currentDbSchemaVersion() else 0,
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
        if (!BackupId.isValid(id)) return@withContext false
        if (readManifest(id) == null) runCatching { external.importInto(root, id) }
        if (readManifest(id) == null) runCatching { cloud.importInto(root, id) }
        verifyArchive(id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (!BackupId.isValid(id)) return@withContext
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
     *
     * § JARVIS Implementation Master Plan PASSAGGIO 10 §G/§H/§I — every step
     * below happens BEFORE the first live byte is touched: id shape, manifest
     * compatibility, every wanted entry's path safety, every source archive's
     * whole-file checksum. Only after ALL of that (and after every entry's own
     * per-file checksum verifies during staging) does cutover begin — and
     * cutover itself replaces each live target from an already-verified
     * staged copy, never straight from the archive. A failure or cancellation
     * at any point before cutover leaves live storage completely unchanged.
     */
    suspend fun restore(id: String, paths: Set<String>? = null): Boolean = withContext(Dispatchers.IO) {
        if (!BackupId.isValid(id)) {
            Log.w(TAG, "restore_refused_bad_id")
            return@withContext false
        }
        // The backup may live only in the destination folder or the cloud (e.g.
        // after a reinstall, or on a brand new device) — pull it into internal
        // storage so the normal path can read it.
        if (readManifest(id) == null) runCatching { external.importInto(root, id) }
        if (readManifest(id) == null) runCatching { cloud.importInto(root, id) }
        val manifest = readManifest(id)
        if (manifest == null) {
            publishDiagnostic(id, manifestAccepted = false, failedAtStage = "manifest")
            Log.w(TAG, "restore_refused id=$id reason=manifest_unreadable")
            return@withContext false
        }

        // Compatibility is judged against what THIS restore will actually
        // touch, not the whole manifest — a selective restore that never
        // asks for db/ must not be refused over a db schema it will never
        // open.
        val wanted = manifest.entries.filter { it.kind == EntryKind.FILE && (paths == null || it.relPath in paths) }
        val hasDbCategory = wanted.any { it.relPath.startsWith("db/") }
        val compatibility = BackupCompatibilityClassifier.classify(
            manifestSchemaVersion = manifest.schemaVersion,
            status = manifest.status,
            archiveSha256 = manifest.archiveSha256,
            hasDbCategory = hasDbCategory,
            dbSchemaVersion = manifest.dbSchemaVersion,
            minSupportedDbSchemaVersion = MIN_SUPPORTED_DB_SCHEMA_VERSION,
            currentDbSchemaVersion = currentDbSchemaVersion(),
        )
        if (compatibility != BackupCompatibility.SUPPORTED_CURRENT && compatibility != BackupCompatibility.SUPPORTED_OLDER) {
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, failedAtStage = "compatibility")
            Log.w(TAG, "restore_refused id=$id compatibility=$compatibility")
            return@withContext false
        }

        if (wanted.any { !BackupPathSafety.isSafeRelPath(it.relPath) }) {
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, failedAtStage = "path_safety")
            Log.w(TAG, "restore_refused id=$id reason=unsafe_path")
            return@withContext false
        }
        // storedInBackupId (an incremental backup's cross-reference to an
        // earlier archive holding an unchanged file's bytes) is also
        // plaintext-manifest-controlled and used to build a filesystem path —
        // the same allow-list applies to it as to the restore id itself.
        val sources = wanted.map { it.storedInBackupId.ifBlank { id } }.toSet()
        if (sources.any { !BackupId.isValid(it) }) {
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, failedAtStage = "path_safety")
            Log.w(TAG, "restore_refused id=$id reason=unsafe_source_id")
            return@withContext false
        }

        // Fetch every archive this restore will read BEFORE touching anything.
        // An incremental backup references earlier archives for files that did
        // not change, and those may live only in the destination folder or the
        // cloud.
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
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = false, failedAtStage = "archive_checksum")
            Log.w(TAG, "restore_refused id=$id unverifiable=${unverifiable.size}")
            return@withContext false
        }

        runBackup() // pre-restore safety snapshot

        // STAGE: decrypt + verify every wanted entry's OWN sha256 (not just
        // the enclosing archive's) into an isolated directory that shares
        // nothing with live storage. Any single failure here — a corrupt zip
        // entry, or a manifest whose declared hash does not match what the
        // authenticated archive actually contains — refuses the ENTIRE
        // restore before any live file is touched (§Q#2/#3).
        RestoreStaging.cleanup(stagingRoot) // never resume a *different* restore's leftovers
        val decrypted = ArrayList<Pair<String, ByteArray>>(wanted.size)
        for (entry in wanted) {
            val sourceBackup = entry.storedInBackupId.ifBlank { id }
            val bytes = extract(sourceBackup, entry.relPath)
            if (bytes == null) {
                publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = true, stagingSucceeded = false, failedAtStage = "extract:${entry.relPath}")
                Log.w(TAG, "restore_refused id=$id reason=extract_failed path=${entry.relPath}")
                return@withContext false
            }
            decrypted += entry.relPath to bytes
        }
        val expectedSha256 = wanted.associate { it.relPath to it.sha256 }
        val failedChecksums = RestoreStaging.stage(stagingRoot, decrypted, expectedSha256)
        if (failedChecksums.isNotEmpty()) {
            RestoreStaging.cleanup(stagingRoot)
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = false, stagingSucceeded = false, failedAtStage = "entry_checksum")
            Log.w(TAG, "restore_refused id=$id reason=entry_checksum_mismatch count=${failedChecksums.size}")
            return@withContext false
        }

        // CUTOVER: only already-staged, already-verified bytes are written to
        // live targets from here on. A process death partway through leaves
        // the staging directory non-empty — its own presence is the recovery
        // marker `completePendingRestoreRecovery()` looks for at next start,
        // and re-applying the same verified bytes again is always safe.
        var ok = true
        var dbCutover = false
        var datastoreCutover = false
        for (entry in wanted) {
            val staged = RestoreStaging.readStaged(stagingRoot, entry.relPath) ?: continue
            if (writeTarget(entry.relPath, staged)) {
                if (entry.relPath.startsWith("db/")) dbCutover = true
                if (entry.relPath.startsWith("datastore/")) datastoreCutover = true
            } else {
                ok = false
            }
        }
        RestoreStaging.cleanup(stagingRoot)

        // Pending-action safety (§J): a restored assistant_tasks row can only
        // be sanitized once Room has re-applied every migration on its own
        // next open — see RestoreSanitizeCallback. Derived-cache invalidation
        // (§C/§K) needs the SAME "after this process's DataStore next loads
        // the restored file fresh" timing, for the same reason — both are
        // therefore deferred to completePendingRestoreRecovery() at next
        // cold start rather than attempted here against still-live state.
        if (dbCutover) runCatching { RestoreSanitizeCallback.markerFile(context).createNewFile() }
        if (datastoreCutover) runCatching { File(root, CLEAR_DERIVED_CACHES_MARKER).createNewFile() }

        publishDiagnostic(
            id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = true,
            stagingSucceeded = true, cutoverSucceeded = ok, recoveryRequired = false,
        )
        ok
    }

    private fun publishDiagnostic(
        id: String,
        manifestAccepted: Boolean,
        compatibility: BackupCompatibility? = null,
        checksumsVerified: Boolean = false,
        stagingSucceeded: Boolean = false,
        cutoverSucceeded: Boolean = false,
        recoveryRequired: Boolean = false,
        failedAtStage: String? = null,
    ) {
        _restoreDiagnostic.value = RestoreDiagnostic(
            backupId = id,
            manifestAccepted = manifestAccepted,
            compatibility = compatibility,
            checksumsVerified = checksumsVerified,
            stagingSucceeded = stagingSucceeded,
            cutoverSucceeded = cutoverSucceeded,
            recoveryRequired = recoveryRequired,
            failedAtStage = failedAtStage,
        )
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
        // Room database (+ its WAL/SHM sidecars if present). § JARVIS
        // Implementation Master Plan PASSAGGIO 10 §D — Room defaults to WAL
        // journal mode, so a naive raw copy of jarvis.db alone (or even
        // alongside a live-drifting -wal/-shm) is not guaranteed a coherent
        // snapshot: a write committed to the WAL but not yet folded into the
        // main file would be silently lost, or the two files could disagree.
        // A TRUNCATE checkpoint folds every committed WAL frame back into
        // jarvis.db and empties the WAL, using SQLite's own supported
        // mechanism (no new redesign) — the .db file alone is then a fully
        // self-consistent snapshot; the (now near-empty) sidecars are still
        // copied alongside for byte-identical round-tripping, not because
        // the .db file depends on them anymore.
        runCatching { checkpointWal() }.onFailure {
            Log.w(TAG, "wal_checkpoint_failed ${it.javaClass.simpleName}")
        }
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

    /** Folds every committed WAL frame back into jarvis.db and empties the WAL. */
    private fun checkpointWal() {
        database.openHelper.writableDatabase
            .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            .use { it.moveToFirst() }
    }

    /** The live `jarvis.db`'s `PRAGMA user_version`, i.e. `JarvisDatabase`'s `@Database(version=)`. */
    private fun currentDbSchemaVersion(): Int = database.openHelper.readableDatabase.version

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
        const val CLEAR_DERIVED_CACHES_MARKER = ".clear_derived_caches"

        /**
         * The earliest `jarvis.db` schema version this build can still open —
         * matches `RuleMigrations.MIGRATION_3_4`, the first migration
         * `DatabaseModule` registers ("Real migrations come first: from
         * version 3 on..."). A staged database older than this has no
         * registered migration path and would otherwise only be reachable via
         * `fallbackToDestructiveMigration()` — silently wiping it rather than
         * refusing (§ JARVIS Implementation Master Plan PASSAGGIO 10 §H).
         */
        const val MIN_SUPPORTED_DB_SCHEMA_VERSION = 3
    }
}
