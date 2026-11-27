package org.deepmatcha.mad.backend.school

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.deepmatcha.mad.contract.*

internal class LocalNotes(private val store: SchoolStore) : NotesRepository {
    override fun observeNoteCourseSummaries() = store.state.map { state ->
        val counts = state.notes().filterNot { it.hidden }.groupingBy { it.folderId }.eachCount()
        state.folders().values.map { folder ->
            NoteCourseSummary(folder.id, folder.sourceCourseId, state.courses[folder.sourceCourseId]?.course?.name,
                folder.displayName, counts[folder.id] ?: 0)
        }
    }.distinctUntilChanged()

    override fun observeNotes(courseId: String?, includeCompleted: Boolean, includeHidden: Boolean, folderId: String?) =
        store.state.map { state ->
            state.notes().filter { note ->
                val matches = if (folderId != null) note.folderId == folderId else courseId == null || note.courseId == courseId
                matches && (includeCompleted || !note.completed) && (includeHidden || !note.hidden)
            }.sortedWith(compareBy<NoteItem> { it.dateAt == null }.thenBy { it.dateAt?.date }.thenBy { it.dateAt?.time }.thenBy { it.id })
        }.distinctUntilChanged()

    override suspend fun getNote(noteId: String) = store.state.value.note(noteId).orNotFound()

    override suspend fun createLocalNote(input: LocalNoteInput) = store.update { state ->
        if (input.title.isBlank()) return@update state to invalid()
        val folderId = input.folderId ?: input.courseId
        val folder = state.folders()[folderId]
        if (folderId != null && folder == null) return@update state to notFound()
        state.save(NoteItem(newId(), NoteKind.LOCAL, folder?.sourceCourseId, folder?.id, input.title, input.body,
            input.dateAt, false, false, null, null))
    }

    override suspend fun updateLocalNote(noteId: String, patch: LocalNotePatch) = store.update { state ->
        val note = state.note(noteId) ?: return@update state to notFound()
        if (note.kind != NoteKind.LOCAL) return@update state to unsupported()
        val title = patch.title.or(note.title)
        if (title.isBlank()) return@update state to invalid()
        val folderId = if (patch.folderId is PatchField.Set || patch.courseId is PatchField.Set)
            patch.folderId.or(null) ?: patch.courseId.or(null) else note.folderId
        val folder = state.folders()[folderId]
        if (folderId != null && folder == null) return@update state to notFound()
        state.save(note.copy(title = title, body = patch.body.or(note.body), courseId = folder?.sourceCourseId,
            folderId = folder?.id, dateAt = patch.dateAt.or(note.dateAt)))
    }

    override suspend fun deleteLocalNote(noteId: String) = store.update { state ->
        val note = state.note(noteId) ?: return@update state to notFound()
        if (note.kind != NoteKind.LOCAL) return@update state to unsupported()
        state.copy(planner = state.planner.copy(notes = state.planner.notes - noteId)) to ApiResult.Success(Unit)
    }

    override suspend fun setNoteCompleted(noteId: String, completed: Boolean) = store.update { state ->
        val note = state.note(noteId) ?: return@update state to notFound()
        when (note.kind) {
            NoteKind.LOCAL -> state.save(note.copy(completed = completed))
            NoteKind.SCHOOL_RESOURCE -> {
                val ids = state.planner.completedNotices
                state.copy(planner = state.planner.copy(completedNotices = if (completed) ids + noteId else ids - noteId)) to
                    ApiResult.Success(note.copy(completed = completed))
            }
            NoteKind.SCHOOL_ASSIGNMENT -> {
                val assignment = state.assignments.getValue(noteId)
                val updated = assignment.copy(overrides = assignment.overrides.copy(userCompleted = completed))
                state.copy(assignments = state.assignments + (noteId to updated)) to ApiResult.Success(updated.asNote())
            }
        }
    }

    override suspend fun createNoteFolder(name: String) = store.update { state ->
        if (name.isBlank()) return@update state to invalid()
        val folder = NoteFolder(newId(), FolderKind.LOCAL, null, name, name)
        state.copy(planner = state.planner.copy(localFolders = state.planner.localFolders + (folder.id to folder))) to ApiResult.Success(folder)
    }

    override suspend fun renameNoteFolder(folderId: String, displayName: String) = store.update { state ->
        if (displayName.isBlank()) return@update state to invalid()
        val folder = state.folders()[folderId]?.copy(displayName = displayName) ?: return@update state to notFound()
        val next = if (folder.kind == FolderKind.LOCAL) state.planner.copy(localFolders = state.planner.localFolders + (folder.id to folder))
            else state.planner.copy(folderNames = state.planner.folderNames + (folder.id to displayName))
        state.copy(planner = next) to ApiResult.Success(folder)
    }

    private fun SchoolState.save(note: NoteItem) =
        copy(planner = planner.copy(notes = planner.notes + (note.id to note))) to ApiResult.Success(note)

    private fun SchoolState.notes() = planner.notes.values + assignments.values.map { it.asNote() } +
        planner.notices.values.map { resourceNote(it) }

    private fun SchoolState.note(id: String) = planner.notes[id] ?: assignments[id]?.asNote() ?: planner.notices[id]?.let { resourceNote(it) }

    private fun StoredAssignment.asNote() = assignment().let {
        NoteItem(it.id, NoteKind.SCHOOL_ASSIGNMENT, it.courseId, it.courseId, it.title, it.description, it.dueAt,
            it.completed, it.hidden, NoteLink(NoteLinkedKind.ASSIGNMENT, it.id), it.source)
    }

    private fun SchoolState.resourceNote(it: Resource) = NoteItem(it.id, NoteKind.SCHOOL_RESOURCE, it.courseId,
        it.courseId, it.title, it.contentText, null, it.id in planner.completedNotices, it.id in planner.hiddenNotices,
        NoteLink(NoteLinkedKind.RESOURCE, it.id), it.source)
}
