package org.deepmatcha.mad.backend.school

import java.time.Instant
import java.time.ZoneId
import org.deepmatcha.mad.contract.*

internal enum class AlertKind { DUE, AVAILABLE, NEW, CLASS_CHANGE, APPROVAL }
internal data class PlannerAlert(val key: String, val itemId: String, val title: String, val kind: AlertKind,
    val at: Long, val expires: Long)
private const val DAY = 86_400_000L
private val zone = ZoneId.of("Asia/Seoul")
private fun KstMoment.exactMillis(): Long? = time?.let { date.atTime(it).atZone(zone).toInstant().toEpochMilli() }

internal fun SchoolState.recordAlerts(before: SchoolState, now: Instant): SchoolState {
    if (this === before) return this
    val at = now.toEpochMilli()
    val next = alerts.filterValues { it.expires > at }.toMutableMap()
    fun add(key: String, id: String, title: String, kind: AlertKind) {
        next[key] = PlannerAlert(key, id, title, kind, at, at + DAY)
    }
    fun classChange(id: String, title: String, old: ClassMode?, new: ClassMode?) {
        if (old != new) next.entries.removeAll { it.value.itemId == id && it.value.kind == AlertKind.CLASS_CHANGE }
        if (notificationSettings.classChangeEnabled && old != new && new in setOf(ClassMode.ONLINE, ClassMode.CANCELLED))
            add("class:$id:${newId()}", id, title, AlertKind.CLASS_CHANGE)
    }
    if (assignments !== before.assignments) for ((id, stored) in assignments) {
        val previous = before.assignments[id]
        if (previous == stored) continue
        val current = stored.assignment()
        if (current.hidden || current.completed) continue
        if (previous == null && notificationSettings.newAssignmentEnabled) add("new:$id", id, current.title, AlertKind.NEW)
        if (notificationSettings.dateChangeApprovalNotificationEnabled) {
            val oldIds = previous?.pendingChanges().orEmpty().map { it.id }.toSet()
            for (change in stored.pendingChanges().filterNot { it.id in oldIds })
                add("approval:${change.id}", id, current.title, AlertKind.APPROVAL)
        }
        classChange(id, current.title, previous?.overrides?.classMode, stored.overrides.classMode)
    }
    if (planner.events !== before.planner.events) for ((id, event) in planner.events) {
        classChange(id, event.title, before.planner.events[id]?.classMode, event.classMode)
        if (id !in before.planner.events && event.kind == CalendarKind.ASSIGNMENT && notificationSettings.newAssignmentEnabled)
            add("new:$id", id, event.title, AlertKind.NEW)
    }
    val valid = next.filterValues { alert ->
        val stored = assignments[alert.itemId]
        val local = planner.events[alert.itemId]
        if (stored == null && local == null || stored?.assignment()?.let { it.hidden || it.completed } == true ||
            local?.let { it.hidden || it.completed == true } == true) false else when (alert.kind) {
            AlertKind.APPROVAL -> notificationSettings.dateChangeApprovalNotificationEnabled &&
                stored?.pendingChanges()?.any { alert.key == "approval:${it.id}" } == true
            AlertKind.CLASS_CHANGE -> notificationSettings.classChangeEnabled
            AlertKind.NEW -> notificationSettings.newAssignmentEnabled
            else -> false
        }
    }
    return if (valid == alerts) this else copy(alerts = valid)
}

internal fun SchoolState.notificationPlan(): List<PlannerAlert> = buildList {
    addAll(alerts.values)
    fun timed(id: String, title: String, kind: AlertKind, start: Long, expires: Long) {
        add(PlannerAlert("${kind.name}:$id:$start", id, title, kind, start, expires))
    }
    fun due(id: String, title: String, date: KstMoment?) {
        val deadline = date?.exactMillis() ?: return
        if (notificationSettings.dueReminderEnabled) timed(id, title, AlertKind.DUE,
            deadline - notificationSettings.dueReminderMinutes * 60000L, deadline + 60000L)
    }
    for (stored in assignments.values) {
        val a = stored.assignment()
        if (a.hidden || a.completed) continue
        due(a.id, a.title, a.dueAt)
        val available = a.availableAt?.exactMillis()
        if (available != null && notificationSettings.assignmentAvailableEnabled)
            timed(a.id, a.title, AlertKind.AVAILABLE, available, minOf(available + DAY, a.dueAt?.exactMillis() ?: Long.MAX_VALUE))
    }
    for (item in planner.events.values) {
        if (item.kind == CalendarKind.ASSIGNMENT && item.completed != true && !item.hidden) due(item.id, item.title, item.startAt)
    }
}
