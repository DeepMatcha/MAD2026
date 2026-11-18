package org.deepmatcha.mad.contract

import java.time.Instant

data class Course(
    val id: String,
    val name: String,
    val source: SourceRef,
    val syncEnabled: Boolean,
    val sync: SyncStatus,
)

data class Assignment(
    val id: String,
    val courseId: String,
    val title: String,
    val description: String?,
    val submissionFormat: String?,
    val submissionMethod: String?,
    val gradingCriteria: String?,
    val availableAt: KstMoment?,
    val dueAt: KstMoment?,
    val schoolCompleted: Boolean,
    val userCompleted: Boolean,
    val hidden: Boolean,
    val source: SourceRef?,
) {
    val completed: Boolean
        get() = schoolCompleted || userCompleted
}

enum class ResourceKind { ASSIGNMENT, NOTICE, FILE, LECTURE, OTHER }

data class Resource(
    val id: String,
    val courseId: String,
    val kind: ResourceKind,
    val title: String,
    val contentText: String?,
    val source: SourceRef,
    val fileInfo: FileInfo?,
)

data class FileInfo(
    val name: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val remoteUpdatedAt: Instant?,
    val downloadUrl: String?,
    val contentHash: String?,
)
