package com.simone.jarvismobile.di

import android.content.Context
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.VaultRepository
import com.simone.jarvismobile.tools.AddReminderTool
import com.simone.jarvismobile.tools.AlarmTool
import com.simone.jarvismobile.tools.BatteryTool
import com.simone.jarvismobile.tools.FlashlightTool
import com.simone.jarvismobile.tools.ListAgendaTool
import com.simone.jarvismobile.tools.ListMemoriesTool
import com.simone.jarvismobile.tools.SearchKnowledgeTool
import com.simone.jarvismobile.tools.RememberTool
import com.simone.jarvismobile.tools.TimeTool
import com.simone.jarvismobile.tools.TimeUntilTool
import com.simone.jarvismobile.tools.TimerTool
import com.simone.jarvismobile.tools.CalendarDraftTool
import com.simone.jarvismobile.tools.CompleteAgendaTool
import com.simone.jarvismobile.tools.DialDraftTool
import com.simone.jarvismobile.tools.ListNotificationsTool
import com.simone.jarvismobile.tools.MediaControlTool
import com.simone.jarvismobile.tools.NavigationTool
import com.simone.jarvismobile.tools.OpenAppTool
import com.simone.jarvismobile.tools.OpenSettingsTool
import com.simone.jarvismobile.tools.PlayMediaTool
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
            ListAgendaTool(agenda),
            CompleteAgendaTool(agenda),
            RememberTool(memory),
            ListMemoriesTool(memory),
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
