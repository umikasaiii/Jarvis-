package com.simone.jarvismobile.core.backup

import com.simone.jarvismobile.core.document.JsonMini

/**
 * Reads and writes the backup [BackupManifest] as JSON. Pure Kotlin (no Android,
 * no serialization dependency) so it is unit-tested off-device: the manifest is
 * the record a restore trusts, so a silent codec bug would be a data-loss bug.
 */
object ManifestCodec {

    const val SCHEMA_VERSION = 1

    fun encode(manifest: BackupManifest): String = buildString {
        append('{')
        field("id", manifest.id); comma()
        field("createdAt", manifest.createdAt); comma()
        field("schemaVersion", manifest.schemaVersion); comma()
        field("appVersion", manifest.appVersion); comma()
        field("status", manifest.status.name); comma()
        field("totalSizeBytes", manifest.totalSizeBytes); comma()
        field("archiveSha256", manifest.archiveSha256); comma()
        append("\"entries\":[")
        manifest.entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('{')
            field("name", e.name); comma()
            field("relPath", e.relPath); comma()
            field("sizeBytes", e.sizeBytes); comma()
            field("sha256", e.sha256); comma()
            field("kind", e.kind.name); comma()
            field("sourceRef", e.sourceRef); comma()
            field("storedInBackupId", e.storedInBackupId)
            append('}')
        }
        append("]}")
    }

    fun decode(json: String): BackupManifest {
        val root = JsonMini.parse(json) as? Map<*, *> ?: error("manifest not an object")
        val entries = (root["entries"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { e ->
            BackupEntry(
                name = str(e["name"]),
                relPath = str(e["relPath"]),
                sizeBytes = long(e["sizeBytes"]),
                sha256 = str(e["sha256"]),
                kind = runCatching { EntryKind.valueOf(str(e["kind"])) }.getOrDefault(EntryKind.FILE),
                sourceRef = str(e["sourceRef"]),
                storedInBackupId = str(e["storedInBackupId"]),
            )
        }
        return BackupManifest(
            id = str(root["id"]),
            createdAt = long(root["createdAt"]),
            schemaVersion = int(root["schemaVersion"]),
            appVersion = str(root["appVersion"]),
            status = runCatching { BackupStatus.valueOf(str(root["status"])) }.getOrDefault(BackupStatus.FAILED),
            totalSizeBytes = long(root["totalSizeBytes"]),
            archiveSha256 = str(root["archiveSha256"]),
            entries = entries,
        )
    }

    // --- encode helpers ---
    private fun StringBuilder.field(key: String, value: String) {
        append('"').append(key).append("\":\"").append(esc(value)).append('"')
    }
    private fun StringBuilder.field(key: String, value: Long) {
        append('"').append(key).append("\":").append(value)
    }
    private fun StringBuilder.field(key: String, value: Int) {
        append('"').append(key).append("\":").append(value)
    }
    private fun StringBuilder.comma() { append(',') }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    // --- decode helpers ---
    private fun str(v: Any?): String = v?.toString() ?: ""
    private fun long(v: Any?): Long = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull() ?: 0L
    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() ?: 0
}
