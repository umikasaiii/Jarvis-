package com.simone.jarvismobile.di

import android.content.Context
import androidx.room.Room
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
            // The document tables are a rebuildable cache (the vault/original files
            // stay the source of truth), so a destructive upgrade is acceptable.
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
}
