package org.deepmatcha.mad.backend.school

import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.deepmatcha.mad.contract.*

internal class LocalTimetable(private val store: SchoolStore) : TimetableRepository {
    override fun observeTimetableEntries() = store.state.map { state ->
        state.planner.timetable.values.sortedWith(compareBy<TimetableEntry> { it.dayOfWeek }.thenBy { it.startTime }.thenBy { it.id })
    }.distinctUntilChanged()

    override suspend fun getTimetableEntry(entryId: String) = store.state.value.planner.timetable[entryId].orNotFound()

    override suspend fun createTimetableEntry(input: TimetableEntryInput) = store.update { state ->
        state.save(TimetableEntry(newId(), input.courseId, input.title, input.dayOfWeek, input.startTime,
            input.endTime, input.location, input.validFrom, input.validUntil))
    }

    override suspend fun updateTimetableEntry(entryId: String, patch: TimetableEntryPatch) = store.update { state ->
        val old = state.planner.timetable[entryId] ?: return@update state to notFound()
        state.save(old.copy(courseId = patch.courseId.or(old.courseId), title = patch.title.or(old.title),
            dayOfWeek = patch.dayOfWeek.or(old.dayOfWeek), startTime = patch.startTime.or(old.startTime),
            endTime = patch.endTime.or(old.endTime), location = patch.location.or(old.location),
            validFrom = patch.validFrom.or(old.validFrom), validUntil = patch.validUntil.or(old.validUntil)))
    }

    override suspend fun deleteTimetableEntry(entryId: String) = store.update { state ->
        if (entryId !in state.planner.timetable) return@update state to notFound()
        state.copy(planner = state.planner.copy(timetable = state.planner.timetable - entryId)) to ApiResult.Success(Unit)
    }

    private fun SchoolState.save(entry: TimetableEntry): Pair<SchoolState, ApiResult<TimetableEntry>> {
        val from = entry.validFrom
        val until = entry.validUntil
        if (entry.title.isBlank() || entry.endTime <= entry.startTime ||
            (from != null && until != null && from > until)) return this to invalid()
        if (entry.courseId != null && entry.courseId !in courses) return this to notFound()
        val next = planner.copy(timetable = planner.timetable + (entry.id to entry))
        return copy(planner = next) to ApiResult.Success(entry)
    }
}

internal fun TimetableEntry.occurrences(range: DateRange): List<CalendarItem> {
    val first = maxOf(range.from, validFrom ?: range.from)
    val last = minOf(range.to, validUntil ?: range.to)
    val weekdayOffset = (dayOfWeek.value - first.dayOfWeek.value + 7) % 7
    if (last.toEpochDay() - first.toEpochDay() < weekdayOffset) return emptyList()
    return generateSequence(first.plusDays(weekdayOffset.toLong())) { date ->
        if (last.toEpochDay() - date.toEpochDay() >= 7) date.plusWeeks(1) else null
    }.map { occurrence(it) }.toList()
}

internal fun SchoolState.timetableOccurrence(itemId: String): CalendarItem? {
    if (':' !in itemId) return null
    val entry = planner.timetable[itemId.substringBefore(':')] ?: return null
    val date = runCatching { LocalDate.parse(itemId.substringAfter(':')) }.getOrNull() ?: return null
    return entry.occurrences(DateRange(date, date)).firstOrNull()
}

private fun TimetableEntry.occurrence(date: LocalDate) = CalendarItem(
    id = "$id:$date",
    origin = Origin.USER,
    kind = CalendarKind.CLASS,
    title = title,
    description = null,
    startAt = KstMoment(date, startTime),
    endAt = KstMoment(date, endTime),
    courseId = courseId,
    assignmentId = null,
    classMode = null,
    location = location,
    timetableEntryId = id,
    completed = null,
    hidden = false,
    source = null,
)
