package com.simone.jarvismobile.core.snapshot

/**
 * The ONE place [RelevantPersonalContext] becomes text or a wire map (§
 * vincolo architetturale "Snapshot ≠ Prompt" — the model stays data
 * everywhere else). Two outputs for two different consumers:
 *
 * - [render] — a compact Italian block prepended to the local model's
 *   prompt, same idea as `JarvisBrain`'s existing `contextBlock`.
 * - [renderForCore] — a minimized `Map<String, String>` for
 *   `JarvisCoreRequest.context` (§ richiesta esplicita: "NON inviare
 *   automaticamente Raw Snapshot completo... non inviare più dati di
 *   quelli necessari"), reusing that field verbatim — no protocol change.
 */
object RelevantContextRenderer {

    fun render(context: RelevantPersonalContext): String {
        val lines = mutableListOf<String>()
        context.temporal?.let {
            lines += "Ora: ${it.date} ${it.time} (${it.dayOfWeek}${it.dayPeriod?.let { p -> ", $p" } ?: ""})"
        }
        context.location?.let {
            val place = when (it.currentPlaceLabel) {
                PlaceLabel.HOME -> "a casa"
                PlaceLabel.WORK -> "al lavoro"
                PlaceLabel.KNOWN_PLACE -> it.currentPlaceName ?: "in un luogo noto"
                PlaceLabel.TRAVELLING -> "in movimento"
                PlaceLabel.UNKNOWN -> null
            }
            if (place != null) lines += "Posizione: l'utente è $place."
        }
        context.driving?.let {
            if (it.isDriving) {
                val dest = it.destination?.let { d -> " verso $d" } ?: ""
                val eta = it.etaMinutes?.let { m -> ", arrivo tra circa $m minuti" } ?: ""
                lines += "Guida: l'utente sta guidando$dest$eta."
            }
        }
        context.agenda?.let { a ->
            a.nextEvent?.let { lines += "Prossimo impegno: ${it.title} (${it.whenText})." }
            a.minutesToNextEvent?.let { lines += "Tempo al prossimo impegno: circa $it minuti." }
            if (a.imminentEvents.size > 1) {
                lines += "Altri impegni imminenti: " + a.imminentEvents.drop(1).joinToString("; ") { "${it.title} (${it.whenText})" } + "."
            }
            if (a.openTasksCount > 0) lines += "Attività aperte: ${a.openTasksCount}."
        }
        context.device?.let {
            val parts = mutableListOf<String>()
            it.batteryLevel?.let { b -> parts += "batteria al $b%" + if (it.isCharging == true) " (in carica)" else "" }
            if (it.isOnline == false) parts += "offline"
            if (parts.isNotEmpty()) lines += "Dispositivo: ${parts.joinToString(", ")}."
        }
        context.memory?.let { m ->
            if (m.items.isNotEmpty()) lines += "Memoria rilevante:\n" + m.items.joinToString("\n") { "- ${it.summary}" }
        }
        context.recentEvents?.let { r ->
            if (r.events.isNotEmpty()) lines += "Eventi recenti: " + r.events.joinToString("; ") { it.type } + "."
        }
        return lines.joinToString("\n")
    }

    /**
     * Deliberately semantic, never raw: place stays a label (never lat/lon),
     * counts stay counts (never full titles) unless the section itself was
     * already left in full detail by the selector (§ minimizzazione §17).
     */
    fun renderForCore(context: RelevantPersonalContext): Map<String, String> {
        val map = mutableMapOf<String, String>()
        context.temporal?.let {
            map["time"] = it.time.toString()
            map["date"] = it.date.toString()
            map["day_of_week"] = it.dayOfWeek.name
            it.dayPeriod?.let { p -> map["day_period"] = p }
        }
        context.location?.let {
            map["current_place"] = it.currentPlaceLabel.name
            map["movement_state"] = it.movementState.name
        }
        context.driving?.let {
            map["is_driving"] = it.isDriving.toString()
            if (it.isDriving) {
                it.etaMinutes?.let { m -> map["eta_minutes"] = m.toString() }
                it.destination?.let { d -> map["destination"] = d }
            }
        }
        context.agenda?.let { a ->
            a.minutesToNextEvent?.let { map["next_event_minutes"] = it.toString() }
            a.nextEvent?.let { map["next_event_title"] = it.title }
            if (a.openTasksCount > 0) map["open_tasks"] = a.openTasksCount.toString()
        }
        context.device?.let {
            it.batteryLevel?.let { b -> map["battery_level"] = b.toString() }
            it.isCharging?.let { c -> map["is_charging"] = c.toString() }
            it.isOnline?.let { o -> map["is_online"] = o.toString() }
        }
        context.task?.let {
            map["active_tasks"] = it.activeTasks.toString()
            map["overdue_tasks"] = it.overdueTasks.toString()
        }
        context.capability?.let {
            map["core_available"] = it.coreAvailable.toString()
            map["memory_available"] = it.memoryAvailable.toString()
        }
        return map
    }
}
