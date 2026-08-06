package com.simone.jarvismobile.background

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AssistantTask::class],
    version = 1,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun assistantTaskDao(): AssistantTaskDao
}
