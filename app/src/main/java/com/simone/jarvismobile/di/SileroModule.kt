package com.simone.jarvismobile.di

import android.content.Context
import com.simone.jarvismobile.audio.silero.OrtSileroInferenceBackend
import com.simone.jarvismobile.audio.silero.SileroModelStore
import com.simone.jarvismobile.audio.silero.SileroVadModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/** Live Voice Phase 0.8 — wires the user-imported Silero model into app-private storage (filesDir). */
@Module
@InstallIn(SingletonComponent::class)
object SileroModule {
    @Provides
    @Singleton
    fun provideSileroVadModelManager(@ApplicationContext context: Context): SileroVadModelManager =
        SileroVadModelManager(
            store = SileroModelStore(context.filesDir),
            inspector = OrtSileroInferenceBackend.inspector,
            backendFactory = OrtSileroInferenceBackend.factory,
            io = Dispatchers.IO,
        )
}
