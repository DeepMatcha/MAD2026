package org.deepmatcha.mad.backend.school

import java.time.Instant
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.KstMoment

/**
 * Fetches a complete assignment delta for one school course. A failed or incomplete response
 * returns [ApiResult.Failure]. A missing row alone does not confirm deletion.
 */
internal interface SchoolAssignmentSource {
    suspend fun fetchChanges(
        externalCourseId: String,
        checkpoint: AssignmentCheckpoint?,
    ): ApiResult<AssignmentDelta>
}

/** A source-specific marker for the last successfully applied delta. */
@JvmInline
internal value class AssignmentCheckpoint(val value: String)

internal data class AssignmentDelta(
    val upserts: List<SchoolAssignmentRecord>,
    val confirmedDeletedExternalIds: Set<String>,
    val nextCheckpoint: AssignmentCheckpoint,
)

/** Source values before applying the app's stable IDs and local user overrides. */
internal data class SchoolAssignmentRecord(
    val externalId: String,
    val url: String?,
    val remoteUpdatedAt: Instant?,
    val title: String,
    val description: String?,
    val submissionFormat: String?,
    val submissionMethod: String?,
    val gradingCriteria: String?,
    val availableAt: KstMoment?,
    val dueAt: KstMoment?,
    val schoolCompleted: Boolean,
)

internal interface SchoolAssignmentSyncStore {
    /** A null checkpoint means this course has not applied a successful delta yet. */
    suspend fun readCheckpoint(courseId: String): AssignmentCheckpoint?

    /**
     * Persists the outcome of an active course attempt. A valid delta, the new checkpoint and
     * job progress are committed together. Invalid or failed responses update status while
     * preserving existing assignments and the last successful checkpoint.
     * Returns Failure if the outcome could not be written to storage.
     */
    suspend fun recordAttempt(
        jobId: String,
        courseId: String,
        expectedCheckpoint: AssignmentCheckpoint?,
        fetched: ApiResult<AssignmentDelta>,
        completedAt: Instant,
    ): ApiResult<Unit>
}
