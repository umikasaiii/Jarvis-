package com.simone.jarvismobile.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.simone.jarvismobile.core.translate.TranslationLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where a per-language offline model is in its lifecycle, for the settings UI. */
enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR }

/**
 * Manages the ML Kit offline translation models via [RemoteModelManager]:
 * checking, downloading (optionally Wi-Fi only) and deleting them. The models
 * are fetched once from Google's servers and then work fully offline; nothing
 * here runs on the translation hot path.
 */
@Singleton
class TranslationModelManager @Inject constructor() {

    private val manager = RemoteModelManager.getInstance()

    private val _statuses = MutableStateFlow<Map<TranslationLanguage, ModelStatus>>(
        TranslationLanguage.entries.associateWith { ModelStatus.NOT_DOWNLOADED },
    )
    val statuses: StateFlow<Map<TranslationLanguage, ModelStatus>> = _statuses.asStateFlow()

    private fun model(lang: TranslationLanguage): TranslateRemoteModel =
        TranslateRemoteModel.Builder(mlKitCode(lang)).build()

    private fun set(lang: TranslationLanguage, status: ModelStatus) {
        _statuses.value = _statuses.value.toMutableMap().apply { put(lang, status) }
    }

    /** Refreshes every language's downloaded state. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val downloaded = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).awaitResult()
                .mapNotNull { TranslationLanguage.fromCode(it.language) }
                .toSet()
        }.getOrDefault(emptySet())
        _statuses.value = TranslationLanguage.entries.associateWith {
            // Don't clobber an in-progress download with a stale "not downloaded".
            if (_statuses.value[it] == ModelStatus.DOWNLOADING) ModelStatus.DOWNLOADING
            else if (it in downloaded) ModelStatus.DOWNLOADED else ModelStatus.NOT_DOWNLOADED
        }
    }

    suspend fun isDownloaded(lang: TranslationLanguage): Boolean = withContext(Dispatchers.IO) {
        runCatching { manager.isModelDownloaded(model(lang)).awaitResult() }.getOrDefault(false)
    }

    /** True only when every language in [langs] is available offline. */
    suspend fun allDownloaded(langs: Collection<TranslationLanguage>): Boolean =
        langs.all { isDownloaded(it) }

    suspend fun download(lang: TranslationLanguage, wifiOnly: Boolean): Boolean = withContext(Dispatchers.IO) {
        set(lang, ModelStatus.DOWNLOADING)
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        val ok = runCatching { manager.download(model(lang), conditions).awaitResult(); true }
            .onFailure { Log.w(TAG, "model_download_failed ${lang.code} ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        set(lang, if (ok) ModelStatus.DOWNLOADED else ModelStatus.ERROR)
        ok
    }

    suspend fun delete(lang: TranslationLanguage): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { manager.deleteDownloadedModel(model(lang)).awaitResult(); true }
            .getOrDefault(false)
        set(lang, if (ok) ModelStatus.NOT_DOWNLOADED else ModelStatus.ERROR)
        ok
    }

    suspend fun downloadAll(wifiOnly: Boolean) {
        TranslationLanguage.entries.forEach { download(it, wifiOnly) }
    }

    suspend fun deleteAll() {
        TranslationLanguage.entries.forEach { delete(it) }
    }

    companion object {
        private const val TAG = "JarvisTranslateModels"

        /** Maps our language to ML Kit's TranslateLanguage tag. */
        fun mlKitCode(lang: TranslationLanguage): String = when (lang) {
            TranslationLanguage.ITALIAN -> TranslateLanguage.ITALIAN
            TranslationLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            TranslationLanguage.SPANISH -> TranslateLanguage.SPANISH
            TranslationLanguage.FRENCH -> TranslateLanguage.FRENCH
            TranslationLanguage.JAPANESE -> TranslateLanguage.JAPANESE
        }
    }
}
