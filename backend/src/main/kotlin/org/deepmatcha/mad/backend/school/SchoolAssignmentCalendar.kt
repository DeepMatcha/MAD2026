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

internal class SchoolAssignmentCalendar(
    private val store: SchoolStore,
    private val clock: Clock,
) : CalendarRepository {
    override fun observeCalendar(range: DateRange, includeCompleted: Boolean?) =
        store.state.map { state ->
            val showCompleted = includeCompleted ?: state.settings.showCompletedItems
            state.assignments.values.mapNotNull { it.calendarItem() }.filter {
                !it.hidden && (showCompleted || it.completed != true) &&
                    it.startAt.date <= range.to && (it.endAt?.date ?: it.startAt.date) >= range.from
            }.sortedWith(compareBy<CalendarItem> { it.startAt.date }.thenBy { it.startAt.time })
        }.distinctUntilChanged()

    override suspend fun getCalendarItem(itemId: String) =
        store.state.value.assignments[itemId]?.calendarItem().orNotFound()

    override suspend fun createLocalEvent(input: LocalEventInput): ApiResult<CalendarItem> =
        ApiResult.Failure(ApiErrorCode.UNSUPPORTED, retryable = false)

    override suspend fun updateCalendarItem(itemId: String, patch: CalendarItemPatch): ApiResult<CalendarItem> =
        store.update { state ->
            val assignment = state.assignments[itemId] ?: return@update state to notFound()
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
        if (itemId in store.state.value.assignments) ApiResult.Failure(ApiErrorCode.UNSUPPORTED, retryable = false)
        else notFound()

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

    private fun StoredAssignment.calendarItem(): CalendarItem? {
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
}

internal fun <T> PatchField<T>.or(current: T): T = when (this) {
    PatchField.Unset -> current
    is PatchField.Set -> value
}
