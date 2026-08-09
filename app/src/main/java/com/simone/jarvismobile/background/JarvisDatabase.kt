package com.simone.jarvismobile.background

import androidx.room.Database
import androidx.room.RoomDatabase
import com.simone.jarvismobile.document.DocumentChunkEntity
import com.simone.jarvismobile.document.DocumentDao
import com.simone.jarvismobile.document.DocumentEntity

@Database(
    entities = [AssistantTask::class, DocumentEntity::class, DocumentChunkEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun assistantTaskDao(): AssistantTaskDao
    abstract fun documentDao(): DocumentDao
}
