package com.simone.jarvismobile.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.background.AssistantTask
import com.simone.jarvismobile.background.AssistantTaskQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val queue: AssistantTaskQueue,
) : ViewModel() {
    val tasks: StateFlow<List<AssistantTask>> = queue.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(id: String) = viewModelScope.launch { queue.cancel(id) }
    fun retry(id: String) = viewModelScope.launch { queue.retry(id) }
}
