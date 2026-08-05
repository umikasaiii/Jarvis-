package com.simone.jarvismobile.ui.commands

import androidx.lifecycle.ViewModel
import com.simone.jarvismobile.tools.ToolRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class CommandsViewModel @Inject constructor(
    runner: ToolRunner,
) : ViewModel() {

    /** name → description for every registered tool (the real capability list). */
    private val _tools = MutableStateFlow(runner.available())
    val tools: StateFlow<List<Pair<String, String>>> = _tools.asStateFlow()
}
