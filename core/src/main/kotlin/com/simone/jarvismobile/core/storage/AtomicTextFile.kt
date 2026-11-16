package com.simone.jarvismobile.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * M1 — a text file that is never left half-written. A write goes to `<name>.tmp` (flushed and fsync'ed), the current good file is copied to
 * `<name>.bak`, and only then the temp file is renamed over the real one (an atomic replace on the POSIX filesystems Android uses). A crash or
 * an I/O error before the rename leaves the old file exactly as it was; the `.bak` always holds the last good version for recovery.
 * Nothing here deletes user data: a stale `.tmp` (an uncommitted write that was never reported as saved) is simply overwritten by the next write.
 *
 * WHAT THIS DOES AND DOES NOT PROMISE. A crash or I/O error before the rename leaves the previous file whole (rename is atomic on the POSIX filesystems
 * Android uses). It does not protect against media corruption or a power loss that the storage hardware/filesystem does not honour after `fsync`; JVM
 * tests simulate an interrupted write, they cannot prove power-loss behaviour. The `.bak` is the last version that passed [worthBackingUp], not a history.
 */
class AtomicTextFile(
    val file: File,
    /** The current file refreshes the `.bak` only if this accepts its content: a main file that is already empty or garbled must not replace the last good backup. */
    private val worthBackingUp: (String) -> Boolean = { it.isNotBlank() },
    private val beforeCommit: () -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")
    val backup = File(file.path + ".bak")

    /** null = the file exists but could not be read; "" = there is no file (a genuinely empty state). */
    fun readOrNull(): String? = try {
        when {
            file.exists() -> file.readText()
            // the main file vanished (not something this class does: it replaces atomically) but the last good copy is there: recover from it
            backup.isFile && backup.length() > 0 -> backup.readText()
            else -> ""
        }
    } catch (e: IOException) {
        null
    }

    /** @throws IOException if the content could not be committed; the previous file is then untouched. */
    fun write(content: String) {
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (file.exists() && runCatching { worthBackingUp(file.readText()) }.getOrDefault(false)) file.copyTo(backup, overwrite = true)
        beforeCommit() // test hook: a crash here must leave the old file intact
        if (!tmp.renameTo(file)) throw IOException("atomic_replace_failed")
    }
}
