package org.deepmatcha.mad.backend.school

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.CalendarItem
import org.deepmatcha.mad.contract.CalendarItemPatch
import org.deepmatcha.mad.contract.CalendarKind
import org.deepmatcha.mad.contract.CalendarRepository
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.DeleteReceipt
import org.deepmatcha.mad.contract.LocalEventInput
import org.deepmatcha.mad.contract.Origin
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.WorkSummary
import org.deepmatcha.mad.contract.ExportState

internal class SchoolAssignmentCalendar(
    private val store: SchoolStore,
    private val clock: Clock,
) : CalendarRepository {
    override fun observeCalendar(range: DateRange, includeCompleted: Boolean?) =
        store.state.map { state ->
            val showCompleted = includeCompleted ?: state.settings.showCompletedItems
            val classes = state.planner.timetable.values.flatMap { it.occurrences(range) }
            val exported = state.google.exports.values.groupBy { it.calendarId }.mapValues { (_, entries) -> entries.map { it.eventId }.toSet() }
            val imported = state.google.cache.flatMap { (calendar, items) ->
                items.filterNot { it.source?.externalId in exported[calendar].orEmpty() }
            }
            (state.assignments.values.mapNotNull { it.calendarItem() } + state.planner.events.values + classes + imported).filter {
                !it.hidden && (showCompleted || it.completed != true) &&
                    it.startAt.date <= range.to && (it.endAt?.date ?: it.startAt.date) >= range.from
            }.sortedWith(compareBy<CalendarItem> { it.startAt.date }.thenBy { it.startAt.time })
        }.distinctUntilChanged()

    override suspend fun getCalendarItem(itemId: String) =
        store.state.value.item(itemId).orNotFound()

    override suspend fun createLocalEvent(input: LocalEventInput): ApiResult<CalendarItem> =
        store.update { state ->
            val item = CalendarItem(newId(), Origin.USER, input.kind, input.title, input.description,
                input.startAt, input.endAt, input.courseId, null, input.classMode, input.location, null,
                if (input.kind == CalendarKind.ASSIGNMENT) false else null, false, null)
            state.saveEvent(item)
        }

    override suspend fun updateCalendarItem(itemId: String, patch: CalendarItemPatch): ApiResult<CalendarItem> =
        store.update { state ->
            val local = state.planner.events[itemId]
            if (local != null) return@update state.saveEvent(local.copy(
                title = patch.title.or(local.title), description = patch.description.or(local.description),
                startAt = patch.startAt.or(local.startAt), endAt = patch.endAt.or(local.endAt),
                classMode = patch.classMode.or(local.classMode), completed = patch.completed.or(local.completed)))
            val assignment = state.assignments[itemId] ?: return@update state to
                if (state.item(itemId) != null) unsupported() else notFound()
            val current = assignment.overrides
            val title = patch.title.or(current.title)
            if (title != null && title.isBlank()) {
                return@update state to ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)
            }
            val description = when (val edit = patch.description) {
                PatchField.Unset -> current.description
                is PatchField.Set -> DescriptionOverride(edit.value)
            }
            val updated = assignment.copy(
                overrides = current.copy(
                    title = title,
                    description = description,
                    dueAt = patch.startAt.or(current.dueAt),
                    endAt = patch.endAt.or(current.endAt),
                    classMode = patch.classMode.or(current.classMode),
                    userCompleted = patch.completed.or(current.userCompleted) == true,
                ),
            )
            val item = updated.calendarItem() ?: return@update state to notFound()
            state.copy(assignments = state.assignments + (itemId to updated)) to ApiResult.Success(item)
        }

    override suspend fun deleteLocalEvent(itemId: String): ApiResult<DeleteReceipt> =
        store.update { state ->
            if (itemId !in state.planner.events) return@update state to
                if (itemId in state.assignments || state.item(itemId) != null) unsupported() else notFound()
            val remote = if (state.google.exports.values.any { it.item.id == itemId }) ExportState.QUEUED else ExportState.NOT_APPLICABLE
            state.copy(planner = state.planner.copy(events = state.planner.events - itemId)) to ApiResult.Success(DeleteReceipt(true, remote))
        }

    private fun SchoolState.saveEvent(item: CalendarItem): Pair<SchoolState, ApiResult<CalendarItem>> {
        val end = item.endAt
        val startTime = item.startAt.time
        val endTime = end?.time
        if (item.title.isBlank() || (end != null && (end.date < item.startAt.date ||
                (end.date == item.startAt.date && endTime != null && startTime != null && endTime < startTime))))
            return this to invalid()
        if (item.courseId != null && item.courseId !in courses) return this to notFound()
        return copy(planner = planner.copy(events = planner.events + (item.id to item))) to ApiResult.Success(item)
    }

    override fun observePendingChanges() = store.state
        .map { state -> state.assignments.values.flatMap { it.pendingChanges() } }
        .distinctUntilChanged()

    override suspend fun resolvePendingChange(changeId: String, approve: Boolean): ApiResult<Unit> =
        store.update { state ->
            val updated = state.assignments.values.firstNotNullOfOrNull {
                it.resolvePendingChange(changeId, approve)
            } ?: return@update state to notFound()
            state.copy(assignments = state.assignments + (updated.id to updated)) to ApiResult.Success(Unit)
        }

    override fun observeWorkSummary(range: DateRange) = store.state.map { state ->
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        val due = state.assignments.values.map { it.assignment() }.filter {
            !it.hidden && it.dueAt?.date?.let { date -> date in range.from..range.to } == true
        }
        val completed = due.count { it.completed }
        WorkSummary(
            total = due.size,
            completed = completed,
            remaining = due.size - completed,
            upcoming = due.count { !it.completed && checkNotNull(it.dueAt).date >= today },
        )
    }.distinctUntilChanged()

}

internal fun SchoolState.item(id: String) = planner.events[id] ?: assignments[id]?.calendarItem() ?: timetableOccurrence(id)
    ?: google.cache.values.firstNotNullOfOrNull { items -> items.find { it.id == id } }

internal fun StoredAssignment.calendarItem(): CalendarItem? {
    val assignment = assignment()
    return CalendarItem(
        id = id,
        origin = Origin.SCHOOL,
        kind = CalendarKind.ASSIGNMENT,
        title = assignment.title,
        description = assignment.description,
        startAt = assignment.dueAt ?: return null,
        endAt = overrides.endAt,
        courseId = courseId,
        assignmentId = id,
        classMode = overrides.classMode,
        location = null,
        timetableEntryId = null,
        completed = assignment.completed,
        hidden = assignment.hidden,
        source = assignment.source,
    )
}

internal fun <T> PatchField<T>.or(current: T): T = when (this) {
    PatchField.Unset -> current
    is PatchField.Set -> value
}
