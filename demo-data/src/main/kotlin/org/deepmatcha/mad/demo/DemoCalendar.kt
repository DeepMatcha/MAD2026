package org.deepmatcha.mad.demo

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.CalendarItem
import org.deepmatcha.mad.contract.CalendarItemPatch
import org.deepmatcha.mad.contract.CalendarKind
import org.deepmatcha.mad.contract.CalendarRepository
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.DeleteReceipt
import org.deepmatcha.mad.contract.ExportState
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.LocalEventInput
import org.deepmatcha.mad.contract.Origin
import org.deepmatcha.mad.contract.TimetableEntry
import org.deepmatcha.mad.contract.WorkSummary

internal class DemoCalendar(
    private val fixtures: DemoFixtures,
    private val local: DemoLocalData,
    private val preferences: DemoSettings,
) : CalendarRepository {
    private val events = MutableStateFlow(fixtures.events)
    private val pending = MutableStateFlow(listOf(fixtures.pending))

    override fun observeCalendar(range: DateRange, includeCompleted: Boolean?) =
        combine(events, local.assignments, local.timetableEntries, preferences.observeSettings()) {
            calendar, assignments, weekly, settings ->
            val showCompleted = includeCompleted ?: settings.showCompletedItems
            val occurrences = weekly.flatMap { it.occurrences(range) }
            (calendar + assignments.mapNotNull { it.calendarItem() } + occurrences).filter {
                it.startAt.date <= range.to && (it.endAt?.date ?: it.startAt.date) >= range.from &&
                    !it.hidden && (showCompleted || it.completed != true)
            }.sortedWith(compareBy<CalendarItem> { it.startAt.date }.thenBy { it.startAt.time })
        }

    override suspend fun getCalendarItem(itemId: String): ApiResult<CalendarItem> {
        val event = events.value.find { it.id == itemId }
            ?: local.assignments.value.find { it.id == itemId }?.calendarItem()
            ?: timetableOccurrence(itemId)
        return event.asResult()
    }

    override suspend fun createLocalEvent(input: LocalEventInput): ApiResult<CalendarItem> {
        if (input.title.isBlank()) return invalidArgument()
        val item = CalendarItem(
            id = newId(),
            origin = Origin.USER,
            kind = input.kind,
            title = input.title,
            description = input.description,
            startAt = input.startAt,
            endAt = input.endAt,
            courseId = input.courseId,
            assignmentId = null,
            classMode = input.classMode,
            location = input.location,
            timetableEntryId = null,
            completed = null,
            hidden = false,
            source = null,
        )
        events.update { it + item }
        return ApiResult.Success(item)
    }

    override suspend fun updateCalendarItem(itemId: String, patch: CalendarItemPatch): ApiResult<CalendarItem> {
        val item = events.value.find { it.id == itemId }
            ?: return if (getCalendarItem(itemId) is ApiResult.Success) unsupported() else null.asResult()
        if (item.origin == Origin.GOOGLE) return unsupported()
        val title = patch.title.or(item.title)
        if (title.isBlank()) return invalidArgument()
        val updated = item.copy(
            title = title,
            description = patch.description.or(item.description),
            startAt = patch.startAt.or(item.startAt),
            endAt = patch.endAt.or(item.endAt),
            classMode = patch.classMode.or(item.classMode),
            completed = patch.completed.or(item.completed),
        )
        events.update { list -> list.map { if (it.id == itemId) updated else it } }
        return ApiResult.Success(updated)
    }

    override suspend fun deleteLocalEvent(itemId: String): ApiResult<DeleteReceipt> {
        val item = events.value.find { it.id == itemId }
            ?: return if (getCalendarItem(itemId) is ApiResult.Success) unsupported() else null.asResult()
        if (item.origin != Origin.USER) return unsupported()
        events.update { list -> list.filterNot { it.id == itemId } }
        return ApiResult.Success(DeleteReceipt(localDeleted = true, remoteState = ExportState.NOT_APPLICABLE))
    }

    override fun observePendingChanges() = pending

    override suspend fun resolvePendingChange(changeId: String, approve: Boolean): ApiResult<Unit> {
        val change = pending.value.find { it.id == changeId } ?: return null.asResult()
        if (approve) {
            val nextDate = LocalDate.parse(checkNotNull(change.newValue))
            events.update { list ->
                list.map { item ->
                    if (item.id != change.itemId) item
                    else item.copy(startAt = item.startAt.copy(date = nextDate), endAt = item.endAt?.copy(date = nextDate))
                }
            }
        }
        pending.update { list -> list.filterNot { it.id == changeId } }
        return ApiResult.Success(Unit)
    }

    override fun observeWorkSummary(range: DateRange) = local.assignments.map { assignments ->
        val visible = assignments.filter { !it.hidden && it.dueAt?.date?.let { date -> date in range.from..range.to } == true }
        WorkSummary(
            total = visible.size,
            completed = visible.count { it.completed },
            remaining = visible.count { !it.completed },
            upcoming = visible.count { !it.completed && checkNotNull(it.dueAt).date >= fixtures.today },
        )
    }

    private fun Assignment.calendarItem(): CalendarItem? {
        val due = dueAt ?: return null
        return CalendarItem(
            id = id,
            origin = Origin.SCHOOL,
            kind = CalendarKind.ASSIGNMENT,
            title = title,
            description = description,
            startAt = due,
            endAt = null,
            courseId = courseId,
            assignmentId = id,
            classMode = null,
            location = null,
            timetableEntryId = null,
            completed = completed,
            hidden = hidden,
            source = source,
        )
    }

    private fun TimetableEntry.occurrences(range: DateRange): List<CalendarItem> {
        val first = maxOf(range.from, validFrom ?: range.from)
        val last = minOf(range.to, validUntil ?: range.to)
        val weekdayOffset = (dayOfWeek.value - first.dayOfWeek.value + 7) % 7
        if (last.toEpochDay() - first.toEpochDay() < weekdayOffset) return emptyList()
        return generateSequence(first.plusDays(weekdayOffset.toLong())) { date ->
            if (last.toEpochDay() - date.toEpochDay() >= 7) date.plusWeeks(1) else null
        }.map { occurrence(it) }.toList()
    }

    private fun timetableOccurrence(itemId: String): CalendarItem? {
        if (':' !in itemId) return null
        val entry = local.timetableEntries.value.find { it.id == itemId.substringBefore(':') } ?: return null
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
}
