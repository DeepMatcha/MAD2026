package org.deepmatcha.mad.backend.school.canvas

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import org.deepmatcha.mad.backend.school.AssignmentCheckpoint
import org.deepmatcha.mad.backend.school.AssignmentDelta
import org.deepmatcha.mad.backend.school.SchoolAssignmentRecord
import org.deepmatcha.mad.backend.school.SchoolAssignmentSource
import org.deepmatcha.mad.backend.school.SchoolCourseRecord
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.KstMoment

private const val ACTIVE_COURSES =
    "/api/v1/courses?enrollment_type=student&enrollment_state=active&include%5B%5D=term&per_page=100"
private val canvasId = Regex("[1-9][0-9]{0,19}")
private val sha256Hex = Regex("[0-9a-f]{64}")
private val seoul = ZoneId.of("Asia/Seoul")

internal class CanvasSchoolSource(private val client: CanvasClient) : SchoolAssignmentSource {
    suspend fun fetchCourses(): ApiResult<List<SchoolCourseRecord>> {
        val rows = when (val response = client.getList(ACTIVE_COURSES)) {
            is ApiResult.Success -> response.data
            is ApiResult.Failure -> return response
        }
        val courses = linkedMapOf<String, SchoolCourseRecord>()
        for (row in rows) {
            val id = row.id("id") ?: return invalidResponse()
            val name = row.text("name")?.takeUnless(String::isBlank) ?: return invalidResponse()
            val course = SchoolCourseRecord(
                externalId = id,
                name = name,
                url = "$CANVAS_WEB/courses/$id",
                remoteUpdatedAt = row.text("updated_at")?.toInstant(),
            )
            if (courses.put(id, course) != null) return invalidResponse()
        }
        return ApiResult.Success(courses.values.toList())
    }

    override suspend fun fetchChanges(
        externalCourseId: String,
        checkpoint: AssignmentCheckpoint?,
    ): ApiResult<AssignmentDelta> {
        if (!canvasId.matches(externalCourseId)) return ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, false)
        // The unfiltered course list gives the full set of assignments visible to this student.
        // Canvas applies the caller's assignment overrides to due_at and unlock_at.
        val resource = "/api/v1/courses/$externalCourseId/assignments"
        val path = "$resource?include%5B%5D=submission&override_assignment_dates=true&per_page=100"
        val rows = when (val response = client.getList(path)) {
            is ApiResult.Success -> response.data
            is ApiResult.Failure -> return response
        }
        val previous = checkpoint?.readSnapshot(externalCourseId).orEmpty()
        val current = sortedMapOf<String, String>()
        val upserts = mutableListOf<SchoolAssignmentRecord>()
        for (row in rows) {
            val assignment = row.assignment(externalCourseId) ?: return invalidResponse()
            val id = assignment.externalId
            val fingerprint = assignment.fingerprint()
            if (current.put(id, fingerprint) != null) return invalidResponse()
            if (fingerprint != previous[id]) upserts.add(assignment)
        }
        return ApiResult.Success(
            AssignmentDelta(
                upserts = upserts,
                confirmedDeletedExternalIds = previous.keys - current.keys,
                nextCheckpoint = current.toCheckpoint(externalCourseId),
            ),
        )
    }
}

private fun JsonObject.assignment(courseId: String): SchoolAssignmentRecord? {
    val id = id("id") ?: return null
    if (value("course_id") != null && id("course_id") != courseId) return null
    val title = text("name")?.takeUnless(String::isBlank) ?: return null
    val methods = textList("submission_types")
    val submission = value("submission") as? JsonObject
    val dueAt = text("due_at")?.toKstMoment()
    return SchoolAssignmentRecord(
        externalId = id,
        url = "$CANVAS_WEB/courses/$courseId/assignments/$id",
        remoteUpdatedAt = text("updated_at")?.toInstant(),
        title = title,
        description = text("description"),
        submissionFormat = if ("online_upload" in methods) textList("allowed_extensions").joinedOrNull() else null,
        submissionMethod = methods.joinedOrNull(),
        gradingCriteria = null,
        availableAt = text("unlock_at")?.toKstMoment(),
        dueAt = if (value("all_day") == JsonPrimitive(true)) {
            text("all_day_date")?.toDateOnly() ?: dueAt?.copy(time = null)
        } else {
            dueAt
        },
        schoolCompleted = submission?.text("submitted_at")?.toInstant() != null ||
            submission?.text("workflow_state") == "submitted",
    )
}

private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless(JsonElement::isJsonNull)

private fun JsonObject.text(key: String): String? =
    (value(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString

private fun JsonObject.id(key: String): String? =
    (value(key) as? JsonPrimitive)?.asString?.takeIf(canvasId::matches)

private fun JsonObject.textList(key: String): List<String> =
    (value(key) as? JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString?.takeUnless(String::isBlank)
    }.orEmpty()

private fun List<String>.joinedOrNull(): String? = takeIf { it.isNotEmpty() }?.joinToString(", ")

private fun String.toInstant(): Instant? = try {
    OffsetDateTime.parse(this).toInstant()
} catch (_: DateTimeParseException) {
    null
}

private fun String.toDateOnly(): KstMoment? = try {
    KstMoment(LocalDate.parse(this), time = null)
} catch (_: DateTimeParseException) {
    null
}

private fun String.toKstMoment(): KstMoment? =
    toInstant()?.atZone(seoul)?.let { KstMoment(it.toLocalDate(), it.toLocalTime()) } ?: toDateOnly()

private fun invalidResponse() = ApiResult.Failure(ApiErrorCode.PARSE_FAILED, retryable = false)

// Fingerprint the values we store, including effective dates and the caller's completion.
// Assignment updated_at alone does not reflect changes to every one of those values.
private fun SchoolAssignmentRecord.fingerprint(): String {
    val values = JsonArray()
    for (field in listOf(
        url,
        remoteUpdatedAt?.toString(),
        title,
        description,
        submissionFormat,
        submissionMethod,
        gradingCriteria,
        availableAt?.toString(),
        dueAt?.toString(),
        schoolCompleted.toString(),
    )) {
        values.add(field)
    }
    val hash = MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}

// The checkpoint carries only IDs and immutable data hashes. Never infer removals
// from a checkpoint written by another source, version, or course.
private fun Map<String, String>.toCheckpoint(courseId: String) = AssignmentCheckpoint(
    buildString {
        append("canvas-v1/")
        append(courseId)
        for ((id, fingerprint) in this@toCheckpoint) {
            append('\n')
            append(id)
            append(':')
            append(fingerprint)
        }
    },
)

private fun AssignmentCheckpoint.readSnapshot(courseId: String): Map<String, String>? {
    val lines = value.lineSequence().iterator()
    if (!lines.hasNext() || lines.next() != "canvas-v1/$courseId") return null
    val snapshot = mutableMapOf<String, String>()
    for (line in lines) {
        val id = line.substringBefore(':')
        val fingerprint = line.substringAfter(':', "")
        if (!canvasId.matches(id) || !sha256Hex.matches(fingerprint) || snapshot.put(id, fingerprint) != null) {
            return null
        }
    }
    return snapshot
}
