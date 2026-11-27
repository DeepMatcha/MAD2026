package org.deepmatcha.mad.backend.school

import org.deepmatcha.mad.contract.*

internal data class GoogleState(
    val account: String? = null,
    val expired: Boolean = false,
    val authRequest: String? = null,
    val pendingRevocation: String? = null,
    val calendars: List<GoogleCalendarInfo> = emptyList(),
    val cache: Map<String, List<CalendarItem>> = emptyMap(),
    val exports: Map<String, GoogleExport> = emptyMap(),
    val savedLinks: Map<String, Map<String, GoogleExport>> = emptyMap(),
) {
    fun disconnect(): GoogleState {
        val links = if (account != null) savedLinks + (account to exports) else savedLinks
        return GoogleState(savedLinks = links)
    }

    fun connect(name: String, calendars: List<GoogleCalendarInfo>): GoogleState {
        if (account == name) return copy(calendars = calendars, authRequest = null, expired = false)
        val links = disconnect().savedLinks
        return GoogleState(account = name, calendars = calendars, exports = links[name].orEmpty(), savedLinks = links - name)
    }
}

internal data class GoogleExport(
    val calendarId: String,
    val eventId: String,
    val item: CalendarItem,
    val pending: Boolean = true,
    val deleting: Boolean = false,
)

internal fun GoogleState.queue(item: CalendarItem, calendarId: String): GoogleState {
    val key = exportKey(item.id, calendarId)
    val previous = exports[key]
    if (previous != null && previous.item == item && !previous.deleting) return this
    val entry = GoogleExport(calendarId, previous?.eventId ?: newId().replace("-", ""), item)
    return copy(exports = exports + (key to entry))
}

internal fun exportKey(itemId: String, calendarId: String) = "${itemId.length}:$itemId$calendarId"

/** Prepare remote writes in the same disk transaction as the user's calendar edit. */
internal fun SchoolState.queueCalendarEdits(previous: SchoolState): SchoolState {
    if (google.account == null ||
        (planner.events === previous.planner.events && planner.timetable === previous.planner.timetable)) return this
    var next = google
    for ((id, item) in planner.events) {
        if (item == previous.planner.events[id]) continue
        val existing = next.exports.values.filter { it.item.id == id }
        for (entry in existing) next = next.queue(item, entry.calendarId)
        val target = settings.googleAutoExportCalendarId
        if (id !in previous.planner.events && settings.googleAutoExport && target != null &&
            google.calendars.any { it.id == target && it.writable }) next = next.queue(item, target)
    }
    val removed = previous.planner.events.keys - planner.events.keys
    if (removed.isNotEmpty()) next = next.copy(exports = next.exports.mapValues { (_, entry) ->
        if (entry.item.id in removed) entry.copy(deleting = true, pending = true) else entry
    })
    if (planner.timetable !== previous.planner.timetable) for ((key, entry) in next.exports) {
        val series = entry.item.timetableEntryId ?: continue
        if (planner.timetable[series] == previous.planner.timetable[series]) continue
        val occurrence = timetableOccurrence(entry.item.id)
        next = if (occurrence == null) next.copy(exports = next.exports + (key to entry.copy(deleting = true, pending = true)))
            else next.queue(occurrence, entry.calendarId)
    }
    return copy(google = next)
}
