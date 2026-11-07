package com.simone.jarvismobile.di

import android.content.Context
import androidx.room.Room
import com.simone.jarvismobile.archive.ArchiveDao
import com.simone.jarvismobile.archive.ArchiveLinkDao
import com.simone.jarvismobile.archive.ArchiveListDao
import com.simone.jarvismobile.automation.rule.AutomationExecutionDao
import com.simone.jarvismobile.automation.rule.AutomationPlaceDao
import com.simone.jarvismobile.automation.rule.AutomationRuleDao
import com.simone.jarvismobile.automation.rule.ParkingLocationDao
import com.simone.jarvismobile.background.AssistantTaskDao
import com.simone.jarvismobile.background.DatabaseOpenGuard
import com.simone.jarvismobile.background.JarvisDatabase
import com.simone.jarvismobile.background.JarvisMigrations
import com.simone.jarvismobile.backup.RestoreSanitizeCallback
import com.simone.jarvismobile.document.DocumentDao
import com.simone.jarvismobile.engine.memory.ConversationalMemoryDao
import com.simone.jarvismobile.navigation.NavDao
import com.simone.jarvismobile.proactive.ProactiveOccurrenceDao
import com.simone.jarvismobile.proactive.ProactiveSchedulePlanDao
import com.simone.jarvismobile.proactive.TriggerEvidenceDao
import com.simone.jarvismobile.responsibility.ResponsibilityDao
import com.simone.jarvismobile.responsibility.ResponsibilityJournalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JarvisDatabase {
        // STEP 2 — refuse (never wipe) a file this build cannot migrate, and keep a
        // pre-upgrade copy before Room runs real migrations. Throws
        // UnsupportedDatabaseVersionException; the file is left untouched.
        DatabaseOpenGuard.preflight(context, "jarvis.db")
        return Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
            // Every version from 1 to the current one has a real migration, registered in
            // JarvisMigrations (contiguity enforced by JarvisMigrationsTest). There is
            // deliberately NO fallbackToDestructiveMigration(): a missing migration must
            // fail loudly, not silently recreate the database empty.
            .addMigrations(*JarvisMigrations.ALL)
            // § JARVIS Implementation Master Plan PASSAGGIO 10 §J — Room's own
            // onOpen() fires only after every migration above has already run,
            // so this is the one safe point to sanitize a just-restored
            // assistant_tasks table against its CURRENT (already migrated)
            // shape. A no-op on every normal start; only runs when
            // BackupRepository.restore() left its marker behind.
            .addCallback(RestoreSanitizeCallback(context))
            .build()
    }

    @Provides
    fun provideAssistantTaskDao(database: JarvisDatabase): AssistantTaskDao =
        database.assistantTaskDao()

    @Provides
    fun provideDocumentDao(database: JarvisDatabase): DocumentDao =
        database.documentDao()

    @Provides
    fun provideNavDao(database: JarvisDatabase): NavDao =
        database.navDao()

    @Provides
    fun provideAutomationRuleDao(database: JarvisDatabase): AutomationRuleDao =
        database.automationRuleDao()

    @Provides
    fun provideAutomationPlaceDao(database: JarvisDatabase): AutomationPlaceDao =
        database.automationPlaceDao()

    @Provides
    fun provideAutomationExecutionDao(database: JarvisDatabase): AutomationExecutionDao =
        database.automationExecutionDao()

    @Provides
    fun provideParkingLocationDao(database: JarvisDatabase): ParkingLocationDao =
        database.parkingLocationDao()

    @Provides
    fun provideArchiveDao(database: JarvisDatabase): ArchiveDao =
        database.archiveDao()

    @Provides
    fun provideArchiveListDao(database: JarvisDatabase): ArchiveListDao =
        database.archiveListDao()

    @Provides
    fun provideArchiveLinkDao(database: JarvisDatabase): ArchiveLinkDao =
        database.archiveLinkDao()

    @Provides
    fun provideConversationalMemoryDao(database: JarvisDatabase): ConversationalMemoryDao =
        database.conversationalMemoryDao()

    @Provides
    fun provideProactiveOccurrenceDao(database: JarvisDatabase): ProactiveOccurrenceDao =
        database.proactiveOccurrenceDao()

    @Provides
    fun provideTriggerEvidenceDao(database: JarvisDatabase): TriggerEvidenceDao =
        database.triggerEvidenceDao()

    @Provides
    fun provideProactiveSchedulePlanDao(database: JarvisDatabase): ProactiveSchedulePlanDao =
        database.proactiveSchedulePlanDao()

    @Provides
    fun provideResponsibilityDao(database: JarvisDatabase): ResponsibilityDao =
        database.responsibilityDao()

    @Provides
    fun provideResponsibilityJournalDao(database: JarvisDatabase): ResponsibilityJournalDao =
        database.responsibilityJournalDao()
}
