package com.simone.jarvismobile.core.backup

/**
 * What a restore should do with a manifest before it ever touches live
 * storage (§ JARVIS Implementation Master Plan PASSAGGIO 10 §H). Every non-
 * [SUPPORTED_CURRENT]/[SUPPORTED_OLDER] value means: refuse before cutover.
 *
 * [UNSUPPORTED_TOO_OLD] is deliberately distinct from [UNSUPPORTED_FUTURE]:
 * a schema this app never shipped a migration for cannot safely open via
 * Room without falling back to a *destructive* migration (`JarvisDatabase`'s
 * `fallbackToDestructiveMigration()`), which would silently wipe the
 * restored data rather than refuse — exactly the "silently dropping a
 * category" the passage forbids. A future schema this build has never heard
 * of is the same kind of refusal for the opposite reason (opening it would
 * either crash Room or silently ignore columns it doesn't recognise).
 */
enum class BackupCompatibility {
    SUPPORTED_CURRENT,
    SUPPORTED_OLDER,
    UNSUPPORTED_FUTURE,
    UNSUPPORTED_TOO_OLD,
    CORRUPT,
    INCOMPLETE,
}

/**
 * Classifies a manifest's compatibility with this app build, pure and
 * deterministic so restore's most consequential decision — "is it even safe
 * to look at this backup's payload" — is unit-tested off-device.
 */
object BackupCompatibilityClassifier {

    /**
     * [manifestSchemaVersion] is the manifest JSON's own shape version
     * ([BackupManifest.schemaVersion]); [supportedManifestSchemaVersion] is
     * this build's [ManifestCodec.SCHEMA_VERSION]. [dbSchemaVersion] is the
     * `jarvis.db` `PRAGMA user_version` recorded at backup time (0 = unknown,
     * e.g. no `db/` category was ever included) — only checked when
     * [hasDbCategory] is true, since a backup with no database entries has
     * nothing Room-schema-shaped to be incompatible with.
     */
    fun classify(
        manifestSchemaVersion: Int,
        status: BackupStatus,
        archiveSha256: String,
        hasDbCategory: Boolean,
        dbSchemaVersion: Int,
        supportedManifestSchemaVersion: Int = ManifestCodec.SCHEMA_VERSION,
        minSupportedDbSchemaVersion: Int,
        currentDbSchemaVersion: Int,
    ): BackupCompatibility {
        if (manifestSchemaVersion < 1) return BackupCompatibility.CORRUPT
        if (manifestSchemaVersion > supportedManifestSchemaVersion) return BackupCompatibility.UNSUPPORTED_FUTURE
        if (status != BackupStatus.COMPLETED) return BackupCompatibility.INCOMPLETE
        if (archiveSha256.isBlank()) return BackupCompatibility.INCOMPLETE

        if (hasDbCategory && dbSchemaVersion > 0) {
            // dbSchemaVersion == 0 means "predates this field" (every backup
            // made before this check existed) — trusted as SUPPORTED_OLDER
            // and left to Room's own registered migrations exactly as before
            // this check was added, never refused for metadata that simply
            // did not exist yet. The version gate below is real protection
            // only for backups new enough to have actually recorded it.
            when {
                dbSchemaVersion > currentDbSchemaVersion -> return BackupCompatibility.UNSUPPORTED_FUTURE
                dbSchemaVersion < minSupportedDbSchemaVersion -> return BackupCompatibility.UNSUPPORTED_TOO_OLD
                dbSchemaVersion < currentDbSchemaVersion -> return BackupCompatibility.SUPPORTED_OLDER
            }
        }
        return BackupCompatibility.SUPPORTED_CURRENT
    }
}
