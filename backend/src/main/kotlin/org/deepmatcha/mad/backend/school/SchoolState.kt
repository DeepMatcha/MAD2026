package org.deepmatcha.mad.backend.school

import java.time.Instant
import java.util.UUID
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.AppSettings
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.ClassMode
import org.deepmatcha.mad.contract.Course
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.NotificationSettings
import org.deepmatcha.mad.contract.PendingChange
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.SourceRef
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.contract.SyncStatus

internal data class SchoolState(
    val courses: Map<String, StoredCourse> = emptyMap(),
    val assignments: Map<String, StoredAssignment> = emptyMap(),
    val syncJobs: Map<String, StoredSyncJob> = emptyMap(),
    val syncStatus: SyncStatus = SyncStatus(SyncState.IDLE, null, null, null),
    val settings: AppSettings = AppSettings(),
    val notificationSettings: NotificationSettings = NotificationSettings(),
)

internal data class SchoolCourseRecord(
    val externalId: String,
    val name: String,
    val url: String? = null,
    val remoteUpdatedAt: Instant? = null,
)

internal data class StoredCourse(
    val course: Course,
    val checkpoint: String? = null,
)

/** A present override with a null value clears the school description. */
internal data class DescriptionOverride(val value: String?)

internal data class AssignmentOverrides(
    val title: String? = null,
    val description: DescriptionOverride? = null,
    val dueAt: KstMoment? = null,
    val endAt: KstMoment? = null,
    val classMode: ClassMode? = null,
    val userCompleted: Boolean = false,
    val hidden: Boolean = false,
)

internal data class StoredAssignment(
    val id: String,
    val courseId: String,
    val remote: SchoolAssignmentRecord,
    val overrides: AssignmentOverrides = AssignmentOverrides(),
    val available: StoredAssignmentDate = StoredAssignmentDate(remote.availableAt),
    val due: StoredAssignmentDate = StoredAssignmentDate(remote.dueAt),
) {
    private val source: SourceRef
        get() = SourceRef(Provider.SCHOOL, remote.externalId, remote.url, remote.remoteUpdatedAt)

    fun assignment() = Assignment(
        id = id,
        courseId = courseId,
        title = overrides.title ?: remote.title,
        description = if (overrides.description == null) remote.description else overrides.description.value,
        submissionFormat = remote.submissionFormat,
        submissionMethod = remote.submissionMethod,
        gradingCriteria = remote.gradingCriteria,
        availableAt = available.accepted,
        dueAt = overrides.dueAt ?: due.accepted,
        schoolCompleted = remote.schoolCompleted,
        userCompleted = overrides.userCompleted,
        hidden = overrides.hidden,
        source = source,
    )

    fun withRemote(record: SchoolAssignmentRecord) = copy(
        remote = record,
        available = available.withRemote(remote.availableAt, record.availableAt),
        due = due.withRemote(remote.dueAt, record.dueAt),
    )

    fun pendingChanges(): List<PendingChange> = listOfNotNull(
        pendingChange(available, "availableAt", available.accepted, remote.availableAt),
        pendingChange(due, "dueAt", overrides.dueAt ?: due.accepted, remote.dueAt),
    )

    fun resolvePendingChange(changeId: String, approve: Boolean): StoredAssignment? = when (changeId) {
        available.pendingId -> copy(available = available.resolve(approve, remote.availableAt))
        due.pendingId -> copy(due = due.resolve(approve, remote.dueAt))
        else -> null
    }

    private fun pendingChange(
        status: StoredAssignmentDate,
        field: String,
        displayed: KstMoment?,
        proposed: KstMoment?,
    ): PendingChange? = status.pendingId?.let { changeId ->
        PendingChange(
            id = changeId,
            itemId = id,
            field = field,
            oldValue = displayed?.encoded(),
            newValue = proposed?.encoded(),
            source = source,
        )
    }
}

private fun KstMoment.encoded(): String = time?.let { "${date}T$it" } ?: date.toString()

internal data class StoredAssignmentDate(
    val accepted: KstMoment?,
    val pendingId: String? = null,
) {
    fun withRemote(previous: KstMoment?, latest: KstMoment?): StoredAssignmentDate = when {
        latest == previous -> this
        latest == accepted -> copy(pendingId = null)
        else -> copy(pendingId = UUID.randomUUID().toString())
    }

    fun resolve(approve: Boolean, remoteValue: KstMoment?) = StoredAssignmentDate(
        accepted = if (approve) remoteValue else accepted,
    )
}

internal fun SchoolState.applyAssignmentDelta(
    courseId: String,
    expectedCheckpoint: AssignmentCheckpoint?,
    delta: AssignmentDelta,
    completedAt: Instant,
): Pair<SchoolState, ApiResult<Unit>> {
    val stored = courses[courseId]
        ?: return this to ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)
    if (stored.checkpoint != expectedCheckpoint?.value || !stored.course.syncEnabled) {
        return this to ApiResult.Failure(ApiErrorCode.CONFLICT, retryable = true)
    }

    val upsertIds = delta.upserts.map(SchoolAssignmentRecord::externalId)
    if (upsertIds.toSet().size != upsertIds.size ||
        upsertIds.any { it.isBlank() || it in delta.confirmedDeletedExternalIds } ||
        delta.confirmedDeletedExternalIds.any(String::isBlank)
    ) {
        return this to ApiResult.Failure(ApiErrorCode.PARSE_FAILED, retryable = false)
    }

    val existingByExternalId = assignments.values.asSequence()
        .filter { it.courseId == courseId }
        .associateBy { it.remote.externalId }
    val updatedAssignments = assignments.toMutableMap()
    for (externalId in delta.confirmedDeletedExternalIds) {
        existingByExternalId[externalId]?.let { updatedAssignments.remove(it.id) }
    }
    for (record in delta.upserts) {
        val assignment = existingByExternalId[record.externalId]?.withRemote(record)
            ?: StoredAssignment(UUID.randomUUID().toString(), courseId, record)
        updatedAssignments[assignment.id] = assignment
    }

    val previous = stored.course.sync
    val status = SyncStatus(
        state = SyncState.SUCCESS,
        lastAttemptAt = previous.lastAttemptAt.takeIf { previous.state == SyncState.RUNNING } ?: completedAt,
        lastSuccessAt = completedAt,
        errorCode = null,
    )
    val updatedCourse = stored.copy(course = stored.course.copy(sync = status), checkpoint = delta.nextCheckpoint.value)
    return copy(courses = courses + (courseId to updatedCourse), assignments = updatedAssignments) to
        ApiResult.Success(Unit)
}

internal fun SchoolState.withCourseFailure(
    courseId: String,
    error: ApiErrorCode,
    attemptedAt: Instant,
): SchoolState {
    val stored = courses[courseId] ?: return this
    val previous = stored.course.sync
    val status = previous.copy(
        state = error.toSyncFailureState(),
        lastAttemptAt = attemptedAt,
        errorCode = error,
    )
    return copy(courses = courses + (courseId to stored.copy(course = stored.course.copy(sync = status))))
}
