package com.simone.jarvismobile.background

import androidx.room.Database
import androidx.room.RoomDatabase
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
    ],
    version = 3,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun assistantTaskDao(): AssistantTaskDao
    abstract fun documentDao(): DocumentDao
    abstract fun navDao(): NavDao
}
