package org.deepmatcha.mad.contract

enum class NoteKind { LOCAL, SCHOOL_ASSIGNMENT, SCHOOL_RESOURCE }
enum class NoteLinkedKind { ASSIGNMENT, RESOURCE }
enum class FolderKind { SCHOOL, LOCAL }

data class NoteItem(
    val id: String,
    val kind: NoteKind,
    val courseId: String?,
    val folderId: String?,
    val title: String,
    val body: String?,
    val dateAt: KstMoment?,
    val completed: Boolean,
    val hidden: Boolean,
    val link: NoteLink?,
    val source: SourceRef?,
)

data class NoteLink(val entityKind: NoteLinkedKind, val entityId: String)

data class NoteFolder(
    val id: String,
    val kind: FolderKind,
    val sourceCourseId: String?,
    val defaultName: String,
    val displayName: String,
)

data class NoteCourseSummary(
    val folderId: String,
    val courseId: String?,
    val courseName: String?,
    val displayName: String,
    val noteCount: Int,
)

data class LocalNoteInput(
    val title: String,
    val body: String?,
    val courseId: String?,
    val folderId: String? = null,
    val dateAt: KstMoment?,
)

data class LocalNotePatch(
    val title: PatchField<String> = PatchField.Unset,
    val body: PatchField<String?> = PatchField.Unset,
    val courseId: PatchField<String?> = PatchField.Unset,
    val folderId: PatchField<String?> = PatchField.Unset,
    val dateAt: PatchField<KstMoment?> = PatchField.Unset,
)
