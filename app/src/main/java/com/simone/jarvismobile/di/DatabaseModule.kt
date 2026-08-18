package com.simone.jarvismobile.di

import android.content.Context
import androidx.room.Room
import com.simone.jarvismobile.archive.ArchiveDao
import com.simone.jarvismobile.archive.ArchiveMigrations
import com.simone.jarvismobile.automation.rule.AutomationExecutionDao
import com.simone.jarvismobile.automation.rule.AutomationPlaceDao
import com.simone.jarvismobile.automation.rule.AutomationRuleDao
import com.simone.jarvismobile.automation.rule.ParkingLocationDao
import com.simone.jarvismobile.automation.rule.RuleMigrations
import com.simone.jarvismobile.background.AssistantTaskDao
import com.simone.jarvismobile.background.JarvisDatabase
import com.simone.jarvismobile.document.DocumentDao
import com.simone.jarvismobile.navigation.NavDao
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
    fun provideDatabase(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
            // Real migrations come first: from version 3 on, the database also
            // holds automation rules and places, which the user typed and which
            // nothing can rebuild. Losing those to a schema bump is not
            // acceptable, so every version from here must ship a migration.
            .addMigrations(*RuleMigrations.ALL, *ArchiveMigrations.ALL)
            // The fallback remains only for the older, cache-only versions (the
            // document and navigation tables can be regenerated from the vault
            // and the original files).
            .fallbackToDestructiveMigration()
            .build()

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
}
