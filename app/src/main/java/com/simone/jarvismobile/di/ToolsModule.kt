package com.simone.jarvismobile.di

import android.content.Context
import com.simone.jarvismobile.core.tools.ToolRegistry
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.tools.AlarmTool
import com.simone.jarvismobile.tools.BatteryTool
import com.simone.jarvismobile.tools.FlashlightTool
import com.simone.jarvismobile.tools.ListMemoriesTool
import com.simone.jarvismobile.tools.RememberTool
import com.simone.jarvismobile.tools.TimeTool
import com.simone.jarvismobile.tools.TimerTool
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
    ): ToolRegistry = ToolRegistry(
        listOf(
            TimeTool(),
            BatteryTool(context),
            TimerTool(context),
            AlarmTool(context),
            FlashlightTool(context),
            RememberTool(memory),
            ListMemoriesTool(memory),
            CalculateTool(),
        ),
    )
}
