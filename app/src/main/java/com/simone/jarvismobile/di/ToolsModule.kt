package com.simone.jarvismobile.di

import android.content.Context
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.VaultRepository
import com.simone.jarvismobile.tools.AddReminderTool
import com.simone.jarvismobile.tools.AddTaskTool
import com.simone.jarvismobile.tools.AlarmTool
import com.simone.jarvismobile.tools.BatteryTool
import com.simone.jarvismobile.tools.FlashlightTool
import com.simone.jarvismobile.tools.ListAgendaTool
import com.simone.jarvismobile.tools.ForgetMemoryTool
import com.simone.jarvismobile.tools.ListMemoriesTool
import com.simone.jarvismobile.tools.SearchKnowledgeTool
import com.simone.jarvismobile.tools.RememberTool
import com.simone.jarvismobile.tools.UpdateMemoryTool
import com.simone.jarvismobile.tools.TimeTool
import com.simone.jarvismobile.tools.TimeUntilTool
import com.simone.jarvismobile.tools.TimerTool
import com.simone.jarvismobile.tools.CalendarDraftTool
import com.simone.jarvismobile.tools.CompleteAgendaTool
import com.simone.jarvismobile.tools.DeleteAgendaTool
import com.simone.jarvismobile.tools.DialDraftTool
import com.simone.jarvismobile.tools.ListNotificationsTool
import com.simone.jarvismobile.tools.MediaControlTool
import com.simone.jarvismobile.tools.MoveMemoryTool
import com.simone.jarvismobile.tools.MoveAgendaTool
import com.simone.jarvismobile.tools.NavigationTool
import com.simone.jarvismobile.tools.OpenAppTool
import com.simone.jarvismobile.tools.OpenSettingsTool
import com.simone.jarvismobile.tools.PlayMediaTool
import com.simone.jarvismobile.tools.RenameAgendaTool
import com.simone.jarvismobile.tools.SearchVaultTool
import com.simone.jarvismobile.tools.SmsDraftTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The complete set of capabilities the assistant may invoke (Phase 6). Adding a
 * tool here is the ONLY way to grant the model a new ability; everything else is
 * rejected by the registry (docs/SECURITY.md §15).
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        memory: MemoryIndex,
        vault: VaultRepository,
        agenda: AgendaRepository,
        knowledge: KnowledgeRepository,
    ): ToolRegistry = ToolRegistry(
        listOf(
            TimeTool(),
            TimeUntilTool(),
            BatteryTool(context),
            TimerTool(context),
            AlarmTool(context),
            FlashlightTool(context),
            AddReminderTool(agenda),
            AddTaskTool(agenda),
            ListAgendaTool(agenda),
            CompleteAgendaTool(agenda),
            DeleteAgendaTool(agenda),
            MoveAgendaTool(agenda),
            RenameAgendaTool(agenda),
            RememberTool(memory),
            ForgetMemoryTool(memory),
            UpdateMemoryTool(memory),
            ListMemoriesTool(memory),
            MoveMemoryTool(memory),
            SearchKnowledgeTool(knowledge),
            OpenAppTool(context),
            OpenSettingsTool(context),
            CalendarDraftTool(context),
            DialDraftTool(context),
            SmsDraftTool(context),
            NavigationTool(context),
            PlayMediaTool(context),
            MediaControlTool(context),
            ListNotificationsTool(context),
            SearchVaultTool(vault),
            CalculateTool(),
        ),
    )
}
