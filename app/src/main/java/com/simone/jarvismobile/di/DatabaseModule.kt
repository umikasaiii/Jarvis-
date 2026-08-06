package com.simone.jarvismobile.di

import android.content.Context
import androidx.room.Room
import com.simone.jarvismobile.background.AssistantTaskDao
import com.simone.jarvismobile.background.JarvisDatabase
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
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db").build()

    @Provides
    fun provideAssistantTaskDao(database: JarvisDatabase): AssistantTaskDao =
        database.assistantTaskDao()
}
