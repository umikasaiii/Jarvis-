package com.simone.jarvismobile.engine

/**
 * Shared contract both orchestrators implement — the only thing
 * [JarvisEngineRouter] knows about either one. `ClassicJarvisEngine` and
 * `ConversationalJarvisEngine` are otherwise unrelated implementations; a
 * future Ibrida engine is a third implementation of the same contract.
 */
fun interface JarvisEngine {
    suspend fun handle(transcript: String): String
}
