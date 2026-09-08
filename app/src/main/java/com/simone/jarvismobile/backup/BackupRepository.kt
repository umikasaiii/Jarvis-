package com.simone.jarvismobile.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import com.simone.jarvismobile.core.backup.ManifestAuthentication
import com.simone.jarvismobile.core.backup.ManifestCodec
import com.simone.jarvismobile.core.backup.Retention
import com.simone.jarvismobile.core.backup.RetentionPolicy
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.memory.VaultRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
     * Finishes an interrupted or deliberately-deferred cutover (§ JARVIS
     * Implementation Master Plan PASSAGGIO 10.1 §4/§5 — `db/`/`datastore/`
     * targets are NEVER cut over from [restore]'s own live process, since
     * that process has already opened Room's live connection in-process by
     * the time it reaches cutover; process death between staging and the
     * last file replace is the other case this same recovery handles — §I:
     * the staging directory's own leftover presence IS the recovery marker,
     * since everything in it already passed its checksum), PROVES restored
     * `assistant_tasks` sanitization actually completed (§ PASSAGGIO 10.3
     * §3/§4/§5 — a successful file cutover alone was not enough), and
     * clears the derived Weather/Health caches a restored `datastore/`
     * category may have brought back stale (§C/§K). A no-op on every
     * normal start; called once at cold start from `JarvisApplication`,
     * BEFORE any other scheduler/reload that might touch the same restored
     * files (§5).
     *
     * § PASSAGGIO 10.2 §1 — returns an explicit [RestoreRecoveryOutcome]
     * instead of `Unit`: the caller MUST be able to tell "nothing was
     * pending" and "recovery finished" apart from "recovery is still
     * incomplete," since only the first two are safe to start restored-
     * state consumers behind. Never throws [kotlinx.coroutines.CancellationException]
     * without propagating it (no `runCatching`/`getOrDefault` anywhere in
     * this function swallows it); any OTHER exception in this function
     * propagates too — turning it into [RestoreRecoveryOutcome.RECOVERY_FAILED]
     * is the caller's job (see `JarvisApplication.onCreate()`), not this
     * function pretending everything is fine.
     */
    suspend fun completePendingRestoreRecovery(): RestoreRecoveryOutcome = withContext(Dispatchers.IO) {
        val sanitizeMarker = RestoreSanitizeCallback.markerFile(context)
        val cacheMarker = File(root, CLEAR_DERIVED_CACHES_MARKER)
        // § §5 — a sanitize marker left over from an earlier cold start
        // whose sanitize kept failing counts as pending work on its own,
        // even with nothing staged this pass.
        val staged = RestoreStaging.stagedRelPaths(stagingRoot)
        val hadPendingWork = staged.isNotEmpty() || cacheMarker.exists() || sanitizeMarker.exists()

        var filesCutoverOk = true
        var sanitizeMarkerPersistedOk = true
        var cacheMarkerPersistedOk = true
        if (staged.isNotEmpty()) {
            Log.i(TAG, "restore_recovery_resuming entries=${staged.size}")
            var allOk = true
            var dbCutover = false
            var datastoreCutover = false
            for (relPath in staged) {
                val stagedFile = RestoreStaging.stagedFile(stagingRoot, relPath) ?: continue
                if (cutoverEntryNow(relPath, stagedFile)) {
                    if (relPath.startsWith("db/")) dbCutover = true
                    if (relPath.startsWith("datastore/")) datastoreCutover = true
                } else {
                    allOk = false
                }
            }
            filesCutoverOk = allOk
            if (allOk) {
                // § §2 — marker persistence IS commit, not a best-effort
                // diagnostic: RecoveryMarker.persist() reports whether the
                // marker is DURABLY present afterward, never just whether
                // createNewFile() itself happened to return true. Written
                // only now, AFTER a real db/datastore cutover actually
                // happened in this (cold-start) process — never
                // speculatively from restore() itself, which never
                // performs this cutover directly (§4 of PASSAGGIO 10.1).
                if (dbCutover) sanitizeMarkerPersistedOk = RecoveryMarker.persist(sanitizeMarker)
                if (datastoreCutover) cacheMarkerPersistedOk = RecoveryMarker.persist(cacheMarker)
                if (sanitizeMarkerPersistedOk && cacheMarkerPersistedOk) {
                    // § §7 — staging has now served its purpose: the marker
                    // file(s) are the durable proof of pending sanitization/
                    // cache-invalidation from here on, never a second ledger.
                    RestoreStaging.cleanup(stagingRoot)
                    Log.i(TAG, "restore_recovery_completed")
                } else {
                    Log.w(TAG, "restore_recovery_marker_persist_failed_will_retry")
                }
            } else {
                Log.w(TAG, "restore_recovery_incomplete_will_retry")
            }
        }

        // § §3/§4/§5 — a sanitize marker (just persisted above, or left
        // over from an earlier cold start) means assistant_tasks
        // sanitization is not yet PROVEN. Safe to deliberately open Room
        // here — the ONLY place this function does — specifically because
        // any db/ file cutover due this pass has already completed above
        // (or none was needed): opening runs every registered migration,
        // then RestoreSanitizeCallback.onOpen() (the sole sanitizer, never
        // duplicated here), and this call then confirms the marker was
        // actually cleared instead of trusting a successful-looking Room
        // open to mean a successful sanitize.
        val sanitizeOk = ensureAssistantTasksSanitized(sanitizeMarker)

        // § §6 — cache-clear ordering: this always runs (even when there
        // was no staged db/datastore work this pass, to retry a marker left
        // by an earlier successful cutover whose own cache-clear attempt
        // failed), and its result feeds the SAME outcome that gates every
        // restored-state consumer below — a failed clear can never look
        // like full success just because the canonical db/datastore
        // cutover itself succeeded.
        val cacheClearOk = clearDerivedCachesIfMarked()

        RestoreRecoveryOutcomeResolver.resolve(
            hadPendingWork = hadPendingWork,
            filesCutoverOk = filesCutoverOk,
            sanitizeMarkerPersistedOk = sanitizeMarkerPersistedOk,
            cacheMarkerPersistedOk = cacheMarkerPersistedOk,
            sanitizeOk = sanitizeOk,
            cacheClearOk = cacheClearOk,
        )
    }

    /** § §3/§4 — see [RecoveryMarker.ensureClearedByOpening]; `database.openHelper.writableDatabase` is the same already-established open pattern [checkpointWal] uses. */
    private fun ensureAssistantTasksSanitized(marker: File): Boolean =
        RecoveryMarker.ensureClearedByOpening(marker) { database.openHelper.writableDatabase }

    /**
     * § §9 — the marker is deleted ONLY when the clear actually succeeded;
     * a failure leaves it in place for a safe retry at the next cold start
     * (this function is only ever called once per start, never in a loop,
     * so "retry" here means "next app launch," not a tight spin).
     *
     * @return true when there is no unresolved derived-cache-clear marker
     *   left afterward — either none existed, or the clear just succeeded.
     */
    private suspend fun clearDerivedCachesIfMarked(): Boolean {
        val marker = File(root, CLEAR_DERIVED_CACHES_MARKER)
        if (!marker.exists()) return true
        val cleared = runCatching {
            settings.setWeatherOutlookCache("")
            settings.setHealthDailyCache("")
        }.isSuccess
        if (cleared) {
            runCatching { marker.delete() }
            Log.i(TAG, "restore_derived_caches_cleared")
            return true
        } else {
            Log.w(TAG, "restore_derived_caches_clear_failed_will_retry")
            return false
        }
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

            val unsigned = BackupManifest(
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
            // § §10 — bind the restore-relevant manifest fields to the same
            // content key that encrypts the archive, so a tamperer with
            // write access to the plaintext manifest.json (mirrored to
            // external/cloud storage) cannot redirect a `storedInBackupId`/
            // `sha256` pair to a different genuine backup without also
            // holding that key.
            val mac = ManifestAuthentication.computeMac(unsigned, keys.contentKey().encoded)
            val manifest = unsigned.copy(manifestMacVersion = ManifestAuthentication.CURRENT_VERSION, manifestMac = mac)
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
        } catch (e: CancellationException) {
            // § §7 — a cancelled backup must never be reported as a failed
            // one, and above all must never swallow the cancellation itself:
            // `Throwable` below would otherwise catch this too.
            throw e
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

        // § §10 — verify the manifest's own authenticity BEFORE trusting any
        // field it declares (dbSchemaVersion/archiveSha256/entries below).
        // manifest.json is plaintext and mirrored to external/cloud storage
        // the user controls; manifestMacVersion==0 is the explicit LEGACY
        // policy for every pre-10.1 backup — accepted for backward
        // compatibility, but never presented as carrying the same integrity
        // guarantee as an authenticated one.
        if (manifest.manifestMacVersion >= 1) {
            val authentic = ManifestAuthentication.verify(manifest, keys.contentKey().encoded)
            if (!authentic) {
                publishDiagnostic(id, manifestAccepted = false, failedAtStage = "manifest_tampered")
                Log.w(TAG, "restore_refused id=$id reason=manifest_tampered")
                return@withContext false
            }
        } else {
            Log.w(TAG, "restore_manifest_legacy_unauthenticated id=$id")
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

        // § §8 — the pre-restore safety backup's result is no longer
        // discarded: if it fails, restore is refused before cutover (the
        // one snapshot the user could fall back to would itself be
        // missing/broken). Cancellation during it now propagates out of
        // restore() unmodified too, since runBackup() no longer swallows
        // CancellationException (§7) and this call is no longer wrapped in
        // anything that would.
        val safetyBackup = runBackup()
        if (safetyBackup == null) {
            publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = true, failedAtStage = "safety_backup")
            Log.w(TAG, "restore_refused id=$id reason=safety_backup_failed")
            return@withContext false
        }

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

        // § §11 — a legacy dbSchemaVersion==0 (or any claimed value) is
        // never trusted alone: inspect the STAGED database's real SQLite
        // schema version directly, one level below BackupCompatibilityClassifier
        // (left unchanged on purpose — reused Room/SQLite knowledge, not a
        // new migration framework), before this staged file is ever cut
        // over live. Runs whenever a db entry is actually staged, not only
        // for the legacy case, as defense-in-depth.
        val stagedDb = RestoreStaging.stagedFile(stagingRoot, "db/jarvis.db")
        if (stagedDb != null) {
            val realVersion = runCatching { readStagedDbSchemaVersion(stagedDb) }.getOrNull()
            val current = currentDbSchemaVersion()
            val withinRange = realVersion != null && realVersion in MIN_SUPPORTED_DB_SCHEMA_VERSION..current
            val matchesClaim = manifest.dbSchemaVersion <= 0 || realVersion == manifest.dbSchemaVersion
            if (!withinRange || !matchesClaim) {
                RestoreStaging.cleanup(stagingRoot)
                publishDiagnostic(id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = true, stagingSucceeded = false, failedAtStage = "staged_db_schema_version")
                Log.w(TAG, "restore_refused id=$id reason=staged_db_schema_version real=$realVersion claimed=${manifest.dbSchemaVersion}")
                return@withContext false
            }
        }

        // CUTOVER: only already-staged, already-verified bytes are written to
        // live targets from here on.
        //
        // § §4 — db/ and datastore/ targets are NEVER cut over from this
        // live process: by this point `restore()` has already opened Room's
        // live connection in-process (via currentDbSchemaVersion() above and
        // in the compatibility check), so this process can never safely
        // rewrite those files out from under that connection. Those two
        // categories are always deferred to `completePendingRestoreRecovery()`
        // at the next cold start — the same existing recovery mechanism,
        // reused rather than a new close/reopen dance. vault/ and generic
        // file targets (documents/ etc.) have no live in-process owner and
        // cut over immediately.
        //
        // A process death partway through — or a deliberate deferral above —
        // leaves the staging directory non-empty. Its own presence is the
        // recovery marker `completePendingRestoreRecovery()` looks for at
        // next start, and re-applying the same verified bytes again (live-
        // safe entries included) is always safe.
        var ok = true
        var deferred = false
        for (entry in wanted) {
            val staged = RestoreStaging.stagedFile(stagingRoot, entry.relPath) ?: continue
            if (entry.relPath.startsWith("db/") || entry.relPath.startsWith("datastore/")) {
                deferred = true
                continue
            }
            if (!cutoverEntryNow(entry.relPath, staged)) ok = false
        }

        // § §3 — cleanup only runs once every write has actually landed and
        // nothing is deferred; a failed live-safe write or a deferred db/
        // datastore cutover both leave the (already fully verified) staging
        // directory in place so recovery can finish or retry, instead of
        // unconditionally discarding evidence of an incomplete cutover.
        if (ok && !deferred) {
            RestoreStaging.cleanup(stagingRoot)
        }

        publishDiagnostic(
            id, manifestAccepted = true, compatibility = compatibility, checksumsVerified = true,
            stagingSucceeded = true, cutoverSucceeded = ok, recoveryRequired = deferred || !ok,
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
        // § §6 — fail CLOSED: a checkpoint failure must abort the whole
        // backup (propagating out to runBackup()'s catch, which reports
        // failure and never writes a COMPLETED manifest), never continue
        // silently onto a db copy that might now be missing committed WAL
        // frames.
        checkpointWal()
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

    /**
     * § §2 — the single shared cutover entry point, used by BOTH [restore]'s
     * live-safe cutover loop and [completePendingRestoreRecovery]'s recovery
     * loop (the old `writeTarget(relPath, bytes)` — a plain non-atomic
     * `.writeBytes()` — has been removed entirely). db/ and datastore/
     * targets go through [RestoreStaging.cutoverOne] (same-directory
     * temp-file + rename, atomic); vault/ targets go through
     * [VaultRepository.writeJarvisFile] (unchanged, still not atomic — out
     * of scope here, see [RestoreStaging]'s own doc comment); any other
     * relPath is a generic file, also atomic via [RestoreStaging.cutoverOne].
     */
    private suspend fun cutoverEntryNow(relPath: String, stagedFile: File): Boolean = runCatching {
        when {
            relPath.startsWith("db/") -> RestoreStaging.cutoverOne(stagedFile, context.getDatabasePath(relPath.removePrefix("db/")))
            relPath.startsWith("datastore/") -> RestoreStaging.cutoverOne(stagedFile, File(context.filesDir, relPath))
            relPath.startsWith("vault/") -> {
                // Write back only JARVIS-owned files into the vault; the user's own
                // notes are left untouched (the vault write grant is scoped to
                // JARVIS/ anyway). A read-only or absent vault simply reports false.
                val vaultRel = relPath.removePrefix("vault/")
                if (vaultRel.startsWith("JARVIS/")) {
                    vault.writeJarvisFile(vaultRel.removePrefix("JARVIS/"), stagedFile.readText())
                } else {
                    true // not JARVIS-owned; skipped, not an error
                }
            }
            else -> RestoreStaging.cutoverOne(stagedFile, File(context.filesDir, relPath))
        }
    }.getOrDefault(false)

    /** The staged database file's real `PRAGMA user_version` (§ §11) — opened read-only, never live. */
    private fun readStagedDbSchemaVersion(file: File): Int {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return try { db.version } finally { db.close() }
    }

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
