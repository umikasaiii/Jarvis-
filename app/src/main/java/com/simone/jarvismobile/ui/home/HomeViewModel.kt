package com.simone.jarvismobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.ChatMessage
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsState
import com.simone.jarvismobile.background.AssistantTaskQueue
import android.net.Uri
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.document.DocumentImportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home adapter. On tap it starts the foreground [ListeningService] (for the
 * ongoing microphone notification) and then runs the Phase-1 session in
 * [viewModelScope] — the app is in the foreground at that moment, so audio
 * capture is always permitted and the state visibly advances immediately.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val coordinator: SessionCoordinator,
    private val taskQueue: AssistantTaskQueue,
    private val documentImporter: DocumentImportManager,
    settings: SettingsRepository,
) : AndroidViewModel(application) {

    /** Imported documents / conversation attachments, for the chat cards. */
    val documents: StateFlow<List<DocumentRecord>> = documentImporter.documents
    val duplicatePrompt: StateFlow<DocumentImportManager.DuplicatePrompt?> = documentImporter.duplicate

    val state: StateFlow<ConversationState> = coordinator.state
    val routeState: StateFlow<AudioRouteState> = coordinator.routeState
    val ttsState: StateFlow<TtsState> = coordinator.ttsState
    val micLevel: StateFlow<Float> = coordinator.micLevel
    val lastError: StateFlow<String?> = coordinator.lastError
    val diagnostic: StateFlow<String> = coordinator.diagnostic
    val transcript: StateFlow<String> = coordinator.transcript
    val reply: StateFlow<String> = coordinator.reply
    val partial: StateFlow<String> = coordinator.partialTranscript
    val messages: StateFlow<List<ChatMessage>> = coordinator.messages
    val sending: StateFlow<Boolean> = combine(coordinator.sending, taskQueue.activeCount) { direct, queued ->
        direct || queued > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val llmLoadState: StateFlow<com.simone.jarvismobile.llm.LlmLoadState> = coordinator.llmLoadState
    val loadedModelName: StateFlow<String?> = coordinator.loadedModelName

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    init {
        // Auto-load the last-used model so it's ready after an app restart without
        // pressing "Carica" again, and build the vault memory index — both in the
        // background (no-ops if nothing is configured).
        viewModelScope.launch { coordinator.ensureModelReady() }
        viewModelScope.launch { coordinator.ensureMemoryReady() }
        viewModelScope.launch { documentImporter.refresh() }
    }

    /** A file was picked from the system picker: import it (off the main thread). */
    fun onDocumentPicked(uri: Uri, saveToVault: Boolean) {
        documentImporter.import(uri, saveToVault)
    }

    fun onRemoveDocument(id: String) = documentImporter.remove(id)
    fun onCancelDocument(id: String) = documentImporter.cancel(id)

    fun onDuplicateUseExisting() = documentImporter.useExisting()
    fun onDuplicateImportAnyway(prompt: DocumentImportManager.DuplicatePrompt) {
        documentImporter.dismissDuplicate()
        documentImporter.import(Uri.parse(prompt.uri), prompt.saveToVault, force = true)
    }
    fun onDismissDuplicate() = documentImporter.dismissDuplicate()

    fun hasRecordPermission(): Boolean = coordinator.hasRecordPermission()

    /**
     * Phase-1 entry point. Runs the session directly in the foreground — NO
     * foreground service — so the capture path is identical to the working
     * Diagnostics "Test microfono". This avoids the MagicOS foreground-service /
     * audio-focus quirk that blocked the main mic while the isolated test worked.
     * Android shows the mic indicator while the Activity is visible, so
     * microphone use stays visible.
     */
    fun onTalkPressed() {
        viewModelScope.launch { coordinator.runSession() }
    }

    fun onCancel() {
        coordinator.cancel()
    }

    fun onInterruptAndTalk() {
        coordinator.interruptAndListen()
    }

    /** Stops the active typed response in WorkManager and inside LiteRT-LM. */
    fun onStopResponse() {
        coordinator.cancelTextGeneration()
        viewModelScope.launch { taskQueue.cancelActive() }
    }

    /** Clears the conversation and the model's in-session memory. */
    fun onNewConversation() {
        coordinator.newConversation()
    }

    /** Sends a typed message (written-chat alternative to voice). */
    fun onSendText(text: String) {
        viewModelScope.launch { taskQueue.enqueueResponse(text) }
    }
}
