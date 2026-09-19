package com.simone.jarvismobile.proactive

import com.simone.jarvismobile.core.proactive.ProactiveTriggerSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE
 * WORK PACKAGE B §5/§6/§15. The single owner of durable schedule-plan
 * reads/writes — [ProactiveScheduler] is the only writer, a fired
 * [com.simone.jarvismobile.alarms.AlarmReceiver] intent the only reader
 * outside of it.
 */
@Singleton
class ProactiveSchedulePlanStore @Inject constructor(
    private val dao: ProactiveSchedulePlanDao,
) {
    suspend fun currentPlan(source: ProactiveTriggerSource): ProactiveSchedulePlanEntity? =
        dao.find(source.name)

    suspend fun upsert(entity: ProactiveSchedulePlanEntity) = dao.upsert(entity)
}
