package com.simone.jarvismobile.core.offlinehub

/**
 * Kiwix hand-over (BETA 6). What the official kiwix-android manifest/code shows (`AndroidManifest.xml`,
 * `KiwixMainActivity.handleZimFileIntent`):
 *  - `ACTION_VIEW` with a `content:`/`file:` URI is accepted (mime `application/octet-stream`, or any type with a
 *    `.zim` path) and given to its external ZIM handler → opens that archive.
 *  - `ACTION_PROCESS_TEXT` (`text/plain`) is declared for searching a selected text. Its exact behaviour is not
 *    verified here, so JARVIS presents it as a best-effort search, not as a guarantee.
 *  - `zim://<id>/<page>` needs Kiwix's internal book id, which JARVIS cannot know → not used.
 * If an intent is not handled by the installed version, JARVIS just opens Kiwix and says how to pick the archive.
 */
object KiwixIntents {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ZIM_MIME = "application/octet-stream"

    fun openZim(contentUri: String, packageId: String?): IntentSpec = IntentSpec(ACTION_VIEW, contentUri, packageId, ZIM_MIME)

    const val OPEN_ZIM_FALLBACK =
        "Ho aperto Kiwix. Questa versione non apre l'archivio direttamente: in Kiwix tocca «Libreria» o l'icona cartella " +
            "e scegli il file ZIM dal telefono."
}

/** ZIM files start with the little-endian magic 72173914 (0x044D495A) = bytes `5A 49 4D 04` ("ZIM\u0004"). */
object ZimHeader {
    val MAGIC = byteArrayOf(0x5A, 0x49, 0x4D, 0x04)
    fun looksLikeZim(first4: ByteArray): Boolean = first4.size >= 4 && (0 until 4).all { first4[it] == MAGIC[it] }
}

/** How reachable one archive is for JARVIS. None of these says Kiwix can open it. */
enum class ZimAccess(val label: String) {
    /** Permission present, file exists and begins with the ZIM magic number. */
    READABLE("Accessibile"),
    PERMISSION_REVOKED("Permesso revocato: riseleziona l'archivio"),
    MISSING("File non trovato (spostato o cancellato)"),
    UNREADABLE("Non leggibile"),
    NOT_A_ZIM("Non sembra un file ZIM (intestazione non valida)"),
}

data class ZimEntry(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val access: ZimAccess,
    /** Whether the Storage Access Framework grant for this URI is still persisted. */
    val persistedPermission: Boolean,
) {
    /**
     * Always false HERE on purpose: whether JARVIS may read an archive is not a property of the file but of an explicit,
     * on-device verification (`ZimKnowledge.verify`, stored with the file size). A test pins this constant.
     */
    val aiQueryable: Boolean get() = false
}

object ZimClassifier {
    fun isZimName(name: String): Boolean = name.endsWith(".zim", ignoreCase = true) || name.endsWith(".zimaa", ignoreCase = true)

    /** Classifies from facts the adapter gathered; never opens more than the first 4 bytes. */
    fun access(permissionPersisted: Boolean, exists: Boolean, canRead: Boolean, first4: ByteArray?): ZimAccess = when {
        !permissionPersisted -> ZimAccess.PERMISSION_REVOKED
        !exists -> ZimAccess.MISSING
        !canRead || first4 == null -> ZimAccess.UNREADABLE
        !ZimHeader.looksLikeZim(first4) -> ZimAccess.NOT_A_ZIM
        else -> ZimAccess.READABLE
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "dimensione sconosciuta"
        bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }
}

/** The honest statements about what JARVIS can and cannot do with a ZIM. */
object ZimPolicy {
    const val NOT_QUERYABLE =
        "Gli archivi ZIM (Wikipedia offline) li apro in Kiwix, ma finché non verifichi la lettura diretta non posso leggerne il contenuto né rispondere da lì " +
            "(la lettura diretta è sperimentale: Biblioteca offline › «Verifica lettura»). Posso rispondere su documenti importati e sulla biblioteca testuale (TXT/Markdown). " +
            "Per un articolo di Wikipedia, dimmi «cerca … su Kiwix» oppure aprilo in Kiwix."
    const val LIBRARY_BANNER =
        "JARVIS apre gli archivi ZIM in Kiwix e non ne legge il contenuto finché non verifichi la lettura diretta su questo telefono (sperimentale): " +
            "l'AI risponde su documenti importati, sulla biblioteca testuale e sugli archivi verificati."
    const val NO_COPY = "Gli archivi non vengono copiati: JARVIS conserva solo il riferimento (sola lettura)."
}

/** The user-chosen external-maps provider for driving commands (default keeps the previous behaviour). */
enum class MapsProvider(val label: String) {
    JARVIS_INTERNAL("Navigatore interno JARVIS"),
    ORGANIC_MAPS("Organic Maps"),
    GOOGLE_MAPS("Google Maps"),
    ;

    companion object {
        val DEFAULT = GOOGLE_MAPS
        fun fromStored(raw: String?): MapsProvider = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
