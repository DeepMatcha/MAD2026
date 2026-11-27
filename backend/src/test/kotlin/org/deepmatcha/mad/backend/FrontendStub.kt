package org.deepmatcha.mad.backend

import org.deepmatcha.mad.contract.MadDependencies

/** Calls the public API as a frontend client would, without requiring an Android UI runtime. */
internal class FrontendStub(private val dependencies: MadDependencies) {
    fun observeCourses() = dependencies.school.observeCourses()

    fun observeAssignments(courseId: String) = dependencies.school.observeAssignments(courseId = courseId)

    fun observeCourseSyncStatus(courseId: String) = dependencies.sync.observeCourseSyncStatus(courseId)

    suspend fun refresh(courseIds: List<String>? = null) = dependencies.sync.startSync(courseIds = courseIds)

    fun observeRefresh(jobId: String) = dependencies.sync.observeSyncJob(jobId)

    suspend fun cancelRefresh(jobId: String) = dependencies.sync.cancelSync(jobId)

    suspend fun retryRefresh(jobId: String) = dependencies.sync.retrySync(jobId)
}
