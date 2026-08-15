package com.simone.jarvismobile.di

import com.simone.jarvismobile.automation.rule.ActionHandler
import com.simone.jarvismobile.automation.rule.CreateReminderActionHandler
import com.simone.jarvismobile.automation.rule.NotifyActionHandler
import com.simone.jarvismobile.automation.rule.RunToolActionHandler
import com.simone.jarvismobile.automation.rule.SpeakActionHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the action handlers the automation engine may use.
 *
 * This set is the complete list of things an automation can do — the same role
 * `ToolsModule` plays for the assistant's tools. An action type with no handler
 * here simply cannot run, and reports that it could not, rather than silently
 * doing nothing (§10, §27).
 *
 * Handlers are provided as an explicit set rather than through scattered
 * `@IntoSet` annotations so that this file alone answers "what can an automation
 * do?", which is a question worth being able to answer by reading one screen.
 */
@Module
@InstallIn(SingletonComponent::class)
object AutomationEngineModule {

    @Provides
    @Singleton
    fun provideActionHandlers(
        notify: NotifyActionHandler,
        speak: SpeakActionHandler,
        reminder: CreateReminderActionHandler,
        runTool: RunToolActionHandler,
    ): Set<ActionHandler> = setOf(notify, speak, reminder, runTool)
}
