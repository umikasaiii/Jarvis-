package com.simone.jarvismobile.di

import com.simone.jarvismobile.audio.AndroidAudioCapture
import com.simone.jarvismobile.audio.AndroidAudioRouteManager
import com.simone.jarvismobile.audio.AndroidOfflineTtsEngine
import com.simone.jarvismobile.audio.AndroidOnDeviceSpeechEngine
import com.simone.jarvismobile.audio.AudioCapture
import com.simone.jarvismobile.audio.AudioRouteManager
import com.simone.jarvismobile.audio.SpeechToTextEngine
import com.simone.jarvismobile.audio.TextToSpeechEngine
import com.simone.jarvismobile.llm.LitertLmEngine
import com.simone.jarvismobile.llm.LlmEngine
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

    @Binds
    @Singleton
    abstract fun bindAudioCapture(impl: AndroidAudioCapture): AudioCapture

    @Binds
    @Singleton
    abstract fun bindSpeechToTextEngine(impl: AndroidOnDeviceSpeechEngine): SpeechToTextEngine

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LitertLmEngine): LlmEngine
}
