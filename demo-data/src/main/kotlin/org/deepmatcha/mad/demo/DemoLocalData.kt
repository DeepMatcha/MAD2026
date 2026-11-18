package org.deepmatcha.mad.demo

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.FolderKind
import org.deepmatcha.mad.contract.LocalNoteInput
import org.deepmatcha.mad.contract.LocalNotePatch
import org.deepmatcha.mad.contract.NoteCourseSummary
import org.deepmatcha.mad.contract.NoteFolder
import org.deepmatcha.mad.contract.NoteItem
import org.deepmatcha.mad.contract.NoteKind
import org.deepmatcha.mad.contract.NoteLink
import org.deepmatcha.mad.contract.NoteLinkedKind
import org.deepmatcha.mad.contract.NotesRepository
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.ResourceKind
import org.deepmatcha.mad.contract.SchoolRepository
import org.deepmatcha.mad.contract.SourceRef
import org.deepmatcha.mad.contract.TimetableEntry
import org.deepmatcha.mad.contract.TimetableEntryInput
import org.deepmatcha.mad.contract.TimetableEntryPatch
import org.deepmatcha.mad.contract.TimetableRepository

internal class DemoLocalData(private val fixtures: DemoFixtures) :
    SchoolRepository, NotesRepository, TimetableRepository {
    private val courses = MutableStateFlow(fixtures.courses)
    internal val assignments = MutableStateFlow(fixtures.assignments)
    internal val timetableEntries = MutableStateFlow(fixtures.timetable)
    private val folders = MutableStateFlow(fixtures.folders)
    private val notes = MutableStateFlow(fixtures.notes)
    private val allNotes = combine(notes, assignments, folders, ::projectNotes)

    override fun observeCourses() = courses

    override suspend fun getCourse(courseId: String) =
        courses.value.find { it.id == courseId }.asResult()

    override suspend fun setCourseSyncEnabled(courseId: String, enabled: Boolean): ApiResult<Unit> {
        if (courses.value.none { it.id == courseId }) return null.asResult()
        courses.update { list ->
            list.map { if (it.id == courseId) it.copy(syncEnabled = enabled) else it }
        }
        return ApiResult.Success(Unit)
    }

    override fun observeAssignments(courseId: String?, includeCompleted: Boolean, includeHidden: Boolean) =
        assignments.map { list ->
            list.filter {
                (courseId == null || it.courseId == courseId) &&
                    (includeCompleted || !it.completed) && (includeHidden || !it.hidden)
            }
        }

    override suspend fun getAssignment(assignmentId: String) =
        assignments.value.find { it.id == assignmentId }.asResult()

    override suspend fun setUserCompleted(assignmentId: String, completed: Boolean): ApiResult<Assignment> {
        val assignment = assignments.value.find { it.id == assignmentId } ?: return null.asResult()
        val updated = assignment.copy(userCompleted = completed)
        assignments.update { list -> list.map { if (it.id == assignmentId) updated else it } }
        return ApiResult.Success(updated)
    }

    override suspend fun setSchoolItemHidden(itemId: String, hidden: Boolean): ApiResult<Unit> {
        val sourceId = currentNotes().find { it.id == itemId }?.link?.entityId ?: itemId
        if (assignments.value.any { it.id == sourceId }) {
            assignments.update { list ->
                list.map { if (it.id == sourceId) it.copy(hidden = hidden) else it }
            }
        } else if (notes.value.any { it.kind == NoteKind.SCHOOL_RESOURCE && it.link?.entityId == sourceId }) {
            notes.update { list ->
                list.map {
                    if (it.link?.entityId == sourceId) it.copy(hidden = hidden) else it
                }
            }
        } else {
            return null.asResult()
        }
        return ApiResult.Success(Unit)
    }

    override fun observeResources(courseId: String, kinds: Set<ResourceKind>?) =
        notes.map { list ->
            val hiddenResourceIds = list.filter { it.hidden }.mapNotNull { it.link?.entityId }.toSet()
            fixtures.resources.filter {
                it.courseId == courseId && (kinds == null || it.kind in kinds) && it.id !in hiddenResourceIds
            }
        }

    override suspend fun getResource(resourceId: String) =
        fixtures.resources.find { it.id == resourceId }.asResult()

    override suspend fun getSource(itemId: String): ApiResult<SourceRef?> {
        val assignment = assignments.value.find { it.id == itemId }
        if (assignment != null) return ApiResult.Success(assignment.source)
        val course = courses.value.find { it.id == itemId }
        if (course != null) return ApiResult.Success(course.source)
        val resource = fixtures.resources.find { it.id == itemId }
        if (resource != null) return ApiResult.Success(resource.source)
        val note = currentNotes().find { it.id == itemId }
        return if (note != null) ApiResult.Success(note.source) else null.asResult()
    }

    override fun observeNoteCourseSummaries() =
        combine(allNotes, folders, courses) { list, currentFolders, currentCourses ->
            currentFolders.map { folder ->
                NoteCourseSummary(
                    folderId = folder.id,
                    courseId = folder.sourceCourseId,
                    courseName = currentCourses.find { it.id == folder.sourceCourseId }?.name,
                    displayName = folder.displayName,
                    noteCount = list.count { it.folderId == folder.id && !it.hidden },
                )
            }
        }

    override fun observeNotes(
        courseId: String?,
        includeCompleted: Boolean,
        includeHidden: Boolean,
        folderId: String?,
    ) = allNotes.map { list ->
        list.filter {
            val groupMatches = if (folderId != null) it.folderId == folderId
                else courseId == null || it.courseId == courseId
            groupMatches && (includeCompleted || !it.completed) && (includeHidden || !it.hidden)
        }
    }

    override suspend fun getNote(noteId: String) = currentNotes().find { it.id == noteId }.asResult()

    override suspend fun createLocalNote(input: LocalNoteInput): ApiResult<NoteItem> {
        if (input.title.isBlank()) return invalidArgument()
        val folder = matchingFolder(input.folderId, input.courseId)
        if (folder == null && (input.folderId != null || input.courseId != null)) return null.asResult()
        val note = NoteItem(
            id = newId(),
            kind = NoteKind.LOCAL,
            courseId = folder?.sourceCourseId,
            folderId = folder?.id,
            title = input.title,
            body = input.body,
            dateAt = input.dateAt,
            completed = false,
            hidden = false,
            link = null,
            source = null,
        )
        notes.update { it + note }
        return ApiResult.Success(note)
    }

    override suspend fun updateLocalNote(noteId: String, patch: LocalNotePatch): ApiResult<NoteItem> {
        val note = currentNotes().find { it.id == noteId } ?: return null.asResult()
        if (note.kind != NoteKind.LOCAL) return unsupported()
        val title = patch.title.or(note.title)
        if (title.isBlank()) return invalidArgument()
        val folder = when {
            patch.folderId is PatchField.Set -> matchingFolder(patch.folderId.or(null), patch.courseId.or(null))
            patch.courseId is PatchField.Set -> matchingFolder(null, patch.courseId.or(null))
            else -> matchingFolder(note.folderId, note.courseId)
        }
        if (folder == null && (patch.folderId.or(null) != null || patch.courseId.or(null) != null)) {
            return null.asResult()
        }
        val updated = note.copy(
            title = title,
            body = patch.body.or(note.body),
            courseId = folder?.sourceCourseId,
            folderId = folder?.id,
            dateAt = patch.dateAt.or(note.dateAt),
        )
        notes.update { list -> list.map { if (it.id == noteId) updated else it } }
        return ApiResult.Success(updated)
    }

    override suspend fun deleteLocalNote(noteId: String): ApiResult<Unit> {
        val note = currentNotes().find { it.id == noteId } ?: return null.asResult()
        if (note.kind != NoteKind.LOCAL) return unsupported()
        notes.update { list -> list.filterNot { it.id == noteId } }
        return ApiResult.Success(Unit)
    }

    override suspend fun setNoteCompleted(noteId: String, completed: Boolean): ApiResult<NoteItem> {
        val note = currentNotes().find { it.id == noteId } ?: return null.asResult()
        if (note.kind == NoteKind.SCHOOL_ASSIGNMENT) {
            setUserCompleted(checkNotNull(note.link).entityId, completed)
        } else {
            notes.update { list -> list.map { if (it.id == noteId) it.copy(completed = completed) else it } }
        }
        return getNote(noteId)
    }

    override suspend fun createNoteFolder(name: String): ApiResult<NoteFolder> {
        if (name.isBlank()) return invalidArgument()
        val folder = NoteFolder(newId(), FolderKind.LOCAL, null, name, name)
        folders.update { it + folder }
        return ApiResult.Success(folder)
    }

    override suspend fun renameNoteFolder(folderId: String, displayName: String): ApiResult<NoteFolder> {
        if (displayName.isBlank()) return invalidArgument()
        val folder = folders.value.find { it.id == folderId } ?: return null.asResult()
        val updated = folder.copy(displayName = displayName)
        folders.update { list -> list.map { if (it.id == folderId) updated else it } }
        return ApiResult.Success(updated)
    }

    override fun observeTimetableEntries() = timetableEntries

    override suspend fun getTimetableEntry(entryId: String) =
        timetableEntries.value.find { it.id == entryId }.asResult()

    override suspend fun createTimetableEntry(input: TimetableEntryInput): ApiResult<TimetableEntry> {
        val entry = TimetableEntry(
            newId(), input.courseId, input.title, input.dayOfWeek, input.startTime, input.endTime,
            input.location, input.validFrom, input.validUntil,
        )
        if (!entry.isValid()) return invalidArgument()
        timetableEntries.update { it + entry }
        return ApiResult.Success(entry)
    }

    override suspend fun updateTimetableEntry(entryId: String, patch: TimetableEntryPatch): ApiResult<TimetableEntry> {
        val entry = timetableEntries.value.find { it.id == entryId } ?: return null.asResult()
        val updated = entry.copy(
            courseId = patch.courseId.or(entry.courseId),
            title = patch.title.or(entry.title),
            dayOfWeek = patch.dayOfWeek.or(entry.dayOfWeek),
            startTime = patch.startTime.or(entry.startTime),
            endTime = patch.endTime.or(entry.endTime),
            location = patch.location.or(entry.location),
            validFrom = patch.validFrom.or(entry.validFrom),
            validUntil = patch.validUntil.or(entry.validUntil),
        )
        if (!updated.isValid()) return invalidArgument()
        timetableEntries.update { list -> list.map { if (it.id == entryId) updated else it } }
        return ApiResult.Success(updated)
    }

    override suspend fun deleteTimetableEntry(entryId: String): ApiResult<Unit> {
        if (timetableEntries.value.none { it.id == entryId }) return null.asResult()
        timetableEntries.update { list -> list.filterNot { it.id == entryId } }
        return ApiResult.Success(Unit)
    }

    private fun TimetableEntry.isValid() =
        title.isNotBlank() && endTime > startTime &&
            (validFrom == null || validUntil == null || checkNotNull(validFrom) <= checkNotNull(validUntil))

    private fun matchingFolder(folderId: String?, courseId: String?): NoteFolder? = when {
        folderId != null -> folders.value.find { it.id == folderId }
        courseId != null -> folders.value.find { it.sourceCourseId == courseId }
        else -> null
    }

    private fun currentNotes() = projectNotes(notes.value, assignments.value, folders.value)

    private fun projectNotes(
        notes: List<NoteItem>,
        assignments: List<Assignment>,
        folders: List<NoteFolder>,
    ): List<NoteItem> {
        val assignmentNotes = assignments.map { assignment ->
            NoteItem(
                id = UUID.nameUUIDFromBytes("note:${assignment.id}".toByteArray()).toString(),
                kind = NoteKind.SCHOOL_ASSIGNMENT,
                courseId = assignment.courseId,
                folderId = folders.find { it.sourceCourseId == assignment.courseId }?.id,
                title = assignment.title,
                body = assignment.description,
                dateAt = assignment.dueAt,
                completed = assignment.completed,
                hidden = assignment.hidden,
                link = NoteLink(NoteLinkedKind.ASSIGNMENT, assignment.id),
                source = assignment.source,
            )
        }
        return (notes + assignmentNotes).sortedWith(
            compareBy<NoteItem> { it.dateAt == null }.thenBy { it.dateAt?.date }.thenBy { it.dateAt?.time },
        )
    }
}
