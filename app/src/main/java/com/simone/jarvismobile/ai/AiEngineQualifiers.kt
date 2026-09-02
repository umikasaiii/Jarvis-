package com.simone.jarvismobile.ai

import javax.inject.Qualifier

/**
 * Distinguishes the two [AiEngine] bindings in the Hilt graph (`di/AiModule.kt`).
 * [AiRouter] depends on the [AiEngine] interface rather than the concrete
 * [LocalAiEngine]/[RemoteAiEngine] classes specifically so a plain-JUnit test
 * can hand it two fake [AiEngine] implementations directly (bypassing Hilt
 * entirely — a normal Kotlin constructor call), matching this project's
 * "Interfaces first... Fakes for tests" convention.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalEngine

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RemoteEngine
