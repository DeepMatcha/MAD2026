package org.deepmatcha.mad.backend.school

import java.time.LocalDate
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.KstMoment

internal object SyntheticSchoolSource : SchoolAssignmentSource {
    val courses = listOf(
        SchoolCourseRecord("sample-networks", "컴퓨터 네트워크"),
        SchoolCourseRecord("sample-databases", "데이터베이스"),
    )

    private val assignments = mapOf(
        "sample-networks" to assignment("sample-packet", "패킷 분석 실습", LocalDate.of(2026, 11, 26)),
        "sample-databases" to assignment("sample-schema", "스키마 설계", null),
    )

    private val checkpoint = AssignmentCheckpoint("1")

    override suspend fun fetchChanges(
        externalCourseId: String,
        checkpoint: AssignmentCheckpoint?,
    ): ApiResult<AssignmentDelta> {
        val assignment = assignments[externalCourseId]
            ?: return ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)
        val upserts = if (checkpoint == this.checkpoint) emptyList() else listOf(assignment)
        return ApiResult.Success(AssignmentDelta(upserts, emptySet(), this.checkpoint))
    }

    private fun assignment(externalId: String, title: String, date: LocalDate?) = SchoolAssignmentRecord(
        externalId = externalId,
        url = null,
        remoteUpdatedAt = null,
        title = title,
        description = null,
        submissionFormat = null,
        submissionMethod = null,
        gradingCriteria = null,
        availableAt = null,
        dueAt = date?.let { KstMoment(it, time = null) },
        schoolCompleted = false,
    )
}
