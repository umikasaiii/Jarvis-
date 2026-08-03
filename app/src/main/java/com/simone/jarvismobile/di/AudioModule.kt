package com.simone.jarvismobile.di

import com.simone.jarvismobile.audio.AndroidAudioRouteManager
import com.simone.jarvismobile.audio.AndroidOfflineTtsEngine
import com.simone.jarvismobile.audio.AudioRouteManager
import com.simone.jarvismobile.audio.TextToSpeechEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the audio engine interfaces to their Android implementations. Every
 * engine is swappable behind its interface (docs/ARCHITECTURE.md §4/§5), so a
 * fake or an alternative backend (sherpa/Piper TTS) can be substituted here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindAudioRouteManager(impl: AndroidAudioRouteManager): AudioRouteManager

    @Binds
    @Singleton
    abstract fun bindTextToSpeechEngine(impl: AndroidOfflineTtsEngine): TextToSpeechEngine
}
