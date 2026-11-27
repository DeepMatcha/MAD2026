package org.deepmatcha.mad.backend.school

import java.util.UUID
import org.deepmatcha.mad.contract.*

internal data class PlannerState(
    val timetable: Map<String, TimetableEntry> = emptyMap(),
    val notes: Map<String, NoteItem> = emptyMap(),
    val localFolders: Map<String, NoteFolder> = emptyMap(),
    val folderNames: Map<String, String> = emptyMap(),
    val notices: Map<String, Resource> = emptyMap(),
    val completedNotices: Set<String> = emptySet(),
    val hiddenNotices: Set<String> = emptySet(),
    val events: Map<String, CalendarItem> = emptyMap(),
)

internal fun newId(): String = UUID.randomUUID().toString()
internal fun invalid() = ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, false)
internal fun unsupported() = ApiResult.Failure(ApiErrorCode.UNSUPPORTED, false)

internal fun SchoolState.folders(): Map<String, NoteFolder> {
    val school = courses.values.map { stored ->
        val course = stored.course
        NoteFolder(course.id, FolderKind.SCHOOL, course.id, course.name, planner.folderNames[course.id] ?: course.name)
    }
    return planner.localFolders + school.associateBy { it.id }
}
