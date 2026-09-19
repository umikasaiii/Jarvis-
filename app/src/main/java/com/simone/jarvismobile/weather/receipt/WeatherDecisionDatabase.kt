package com.simone.jarvismobile.weather.receipt

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §22/§29. A SEPARATE, dedicated Room database for weather
 * decision receipts — deliberately NOT a table inside
 * [com.simone.jarvismobile.background.JarvisDatabase].
 *
 * **Disclosed, reasoned deviation from §33's literal wording** (which
 * assumes a shared-DB migration): §29 requires receipts/detailed evidence to
 * stay LOCAL by default and be excluded/redacted from normal exported
 * backups. [com.simone.jarvismobile.backup.BackupRepository.collectSources]
 * sweeps the `db/` directory by an EXPLICIT file-name list
 * (`jarvis.db`/`-wal`/`-shm`) — never a generic directory walk — so a
 * second, separately-named database file (`weather_decisions.db`) is simply
 * never referenced by that list and is therefore excluded from every backup
 * BY CONSTRUCTION, with zero redaction code and zero `JarvisDatabase`
 * migration. This satisfies §29's privacy requirement more directly than
 * folding this table into the shared, backed-up database would have, at the
 * cost of a second Room instance — a deliberate trade-off, not an
 * oversight, disclosed here and in the Work Package D final report.
 *
 * No migration is needed FROM this database's own prior state because it
 * did not exist before this work package; nothing here touches
 * [com.simone.jarvismobile.background.JarvisDatabase]'s own version/migration
 * chain (§33 — "inspect the actual current DB version first" was done:
 * confirmed 14, unaffected by this separate file).
 */
@Database(
    entities = [ForecastDecisionReceiptEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WeatherDecisionDatabase : RoomDatabase() {
    abstract fun forecastDecisionReceiptDao(): ForecastDecisionReceiptDao
}
