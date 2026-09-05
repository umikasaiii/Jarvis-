package com.simone.jarvismobile.di

import android.content.Context
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.archive.ArchiveListRepository
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.archive.PersonalArchiveSearch
import com.simone.jarvismobile.document.DocumentImportManager
import com.simone.jarvismobile.health.HealthConnectManager
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.VaultRepository
import com.simone.jarvismobile.tools.AddListItemTool
import com.simone.jarvismobile.tools.AddReminderTool
import com.simone.jarvismobile.tools.AddTaskTool
import com.simone.jarvismobile.tools.AlarmTool
import com.simone.jarvismobile.tools.BatteryTool
import com.simone.jarvismobile.tools.CreateArchiveItemTool
import com.simone.jarvismobile.tools.CreateListTool
import com.simone.jarvismobile.tools.DeleteArchiveItemTool
import com.simone.jarvismobile.tools.FlashlightTool
import com.simone.jarvismobile.tools.GetDeviceInfoTool
import com.simone.jarvismobile.tools.GetHealthSummaryTool
import com.simone.jarvismobile.tools.GetWeatherTool
import com.simone.jarvismobile.tools.ListAgendaTool
import com.simone.jarvismobile.tools.ForgetMemoryTool
import com.simone.jarvismobile.tools.ListItemsTool
import com.simone.jarvismobile.tools.ListMemoriesTool
import com.simone.jarvismobile.tools.ReadArchiveItemTool
import com.simone.jarvismobile.tools.ReadDocumentContextTool
import com.simone.jarvismobile.tools.RemoveListItemTool
import com.simone.jarvismobile.tools.SearchArchiveTool
import com.simone.jarvismobile.tools.SearchDocumentsTool
import com.simone.jarvismobile.tools.SearchImagesTool
import com.simone.jarvismobile.tools.SearchKnowledgeTool
import com.simone.jarvismobile.tools.SearchMemoryTool
import com.simone.jarvismobile.tools.RememberTool
import com.simone.jarvismobile.tools.UpdateArchiveItemTool
import com.simone.jarvismobile.tools.UpdateListItemTool
import com.simone.jarvismobile.tools.UpdateMemoryTool
import com.simone.jarvismobile.tools.TimeTool
import com.simone.jarvismobile.tools.TimeUntilTool
import com.simone.jarvismobile.tools.TimerTool
import com.simone.jarvismobile.tools.CalendarDraftTool
import com.simone.jarvismobile.tools.CompleteAgendaTool
import com.simone.jarvismobile.tools.DeleteAgendaTool
import com.simone.jarvismobile.tools.DialDraftTool
import com.simone.jarvismobile.tools.HideDrivingPanelTool
import com.simone.jarvismobile.tools.ListNotificationsTool
import com.simone.jarvismobile.tools.MediaControlTool
import com.simone.jarvismobile.tools.MoveMemoryTool
import com.simone.jarvismobile.tools.MoveAgendaTool
import com.simone.jarvismobile.tools.NavigationTool
import com.simone.jarvismobile.tools.OpenAppTool
import com.simone.jarvismobile.tools.OpenSettingsTool
import com.simone.jarvismobile.tools.PlayMediaTool
import com.simone.jarvismobile.tools.QueryAgendaTool
import com.simone.jarvismobile.tools.RenameAgendaTool
import com.simone.jarvismobile.tools.UpdateAgendaNotesTool
import com.simone.jarvismobile.tools.ReplyMessageTool
import com.simone.jarvismobile.tools.SearchVaultTool
import com.simone.jarvismobile.tools.SetDrivingNavigationTool
import com.simone.jarvismobile.tools.ShowDrivingPanelTool
import com.simone.jarvismobile.tools.SmsDraftTool
import com.simone.jarvismobile.tools.StartDrivingModeTool
import com.simone.jarvismobile.tools.StartDrivingRouteTool
import com.simone.jarvismobile.tools.StopDrivingModeTool
import com.simone.jarvismobile.driving.DrivingModeManager
import com.simone.jarvismobile.driving.DrivingNotificationController
import com.simone.jarvismobile.weather.WeatherManager
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
        archive: ArchiveRepository,
        archiveLists: ArchiveListRepository,
        archiveSearch: PersonalArchiveSearch,
        documents: DocumentImportManager,
        drivingMode: DrivingModeManager,
        drivingNotifications: DrivingNotificationController,
        weather: WeatherManager,
        health: HealthConnectManager,
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
            QueryAgendaTool(agenda),
            CompleteAgendaTool(agenda),
            DeleteAgendaTool(agenda),
            MoveAgendaTool(agenda),
            RenameAgendaTool(agenda),
            UpdateAgendaNotesTool(agenda),
            RememberTool(memory),
            ForgetMemoryTool(memory),
            UpdateMemoryTool(memory),
            ListMemoriesTool(memory),
            MoveMemoryTool(memory),
            SearchKnowledgeTool(knowledge),
            SearchMemoryTool(memory),
            SearchArchiveTool(archiveSearch),
            SearchDocumentsTool(documents),
            ReadDocumentContextTool(documents),
            SearchImagesTool(documents),
            CreateArchiveItemTool(archive),
            ReadArchiveItemTool(archive),
            UpdateArchiveItemTool(archive),
            DeleteArchiveItemTool(archive),
            ListItemsTool(archiveLists, archive),
            CreateListTool(archiveLists),
            AddListItemTool(archiveLists),
            UpdateListItemTool(archiveLists),
            RemoveListItemTool(archiveLists),
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
            StartDrivingModeTool(drivingMode),
            StopDrivingModeTool(drivingMode),
            SetDrivingNavigationTool(context, drivingMode),
            StartDrivingRouteTool(context, drivingMode),
            ShowDrivingPanelTool(drivingMode),
            HideDrivingPanelTool(drivingMode),
            ReplyMessageTool(drivingNotifications),
            GetWeatherTool(weather),
            GetHealthSummaryTool(health),
            GetDeviceInfoTool(context),
        ),
    )
}
