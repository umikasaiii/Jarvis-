package com.simone.jarvismobile.background

import androidx.room.Database
import androidx.room.RoomDatabase
import com.simone.jarvismobile.archive.ArchiveDao
import com.simone.jarvismobile.archive.ArchiveItemEntity
import com.simone.jarvismobile.archive.ArchiveLinkDao
import com.simone.jarvismobile.archive.ArchiveLinkEntity
import com.simone.jarvismobile.archive.ArchiveListDao
import com.simone.jarvismobile.archive.ArchiveListEntity
import com.simone.jarvismobile.archive.ArchiveListItemEntity
import com.simone.jarvismobile.automation.rule.AutomationExecutionDao
import com.simone.jarvismobile.automation.rule.AutomationExecutionEntity
import com.simone.jarvismobile.automation.rule.AutomationPlaceDao
import com.simone.jarvismobile.automation.rule.AutomationPlaceEntity
import com.simone.jarvismobile.automation.rule.AutomationRuleDao
import com.simone.jarvismobile.automation.rule.AutomationRuleEntity
import com.simone.jarvismobile.automation.rule.ParkingLocationDao
import com.simone.jarvismobile.automation.rule.ParkingLocationEntity
import com.simone.jarvismobile.document.DocumentChunkEntity
import com.simone.jarvismobile.document.DocumentDao
import com.simone.jarvismobile.document.DocumentEntity
import com.simone.jarvismobile.navigation.NavDao
import com.simone.jarvismobile.navigation.NavFavoriteEntity
import com.simone.jarvismobile.navigation.NavHistoryEntity
import com.simone.jarvismobile.navigation.PlaceFtsEntity

@Database(
    entities = [
        AssistantTask::class, DocumentEntity::class, DocumentChunkEntity::class,
        PlaceFtsEntity::class, NavFavoriteEntity::class, NavHistoryEntity::class,
        AutomationRuleEntity::class, AutomationPlaceEntity::class,
        AutomationExecutionEntity::class, ParkingLocationEntity::class,
        ArchiveItemEntity::class, ArchiveListEntity::class, ArchiveListItemEntity::class, ArchiveLinkEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun assistantTaskDao(): AssistantTaskDao
    abstract fun documentDao(): DocumentDao
    abstract fun navDao(): NavDao

    // Automation engine. Unlike the tables above, these hold data only the user
    // can produce — see RuleMigrations for why they must never be migrated
    // destructively.
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun automationPlaceDao(): AutomationPlaceDao
    abstract fun automationExecutionDao(): AutomationExecutionDao
    abstract fun parkingLocationDao(): ParkingLocationDao

    // Modalità Pro's personal archive (notes/to-watch) — user-typed data, same
    // "never destructive" rule as the automation tables. See ArchiveMigrations.
    abstract fun archiveDao(): ArchiveDao

    // Personal Archive lists (shopping + custom) and cross-entity links — same
    // non-destructive migration rule. See ArchiveMigrations.MIGRATION_5_6.
    abstract fun archiveListDao(): ArchiveListDao
    abstract fun archiveLinkDao(): ArchiveLinkDao
}
