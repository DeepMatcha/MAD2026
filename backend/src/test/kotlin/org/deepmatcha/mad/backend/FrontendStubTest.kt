package org.deepmatcha.mad.backend

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.demo.DemoMadDependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontendStubTest {
    private val frontend = FrontendStub(DemoMadDependencies.create())

    @Test
    fun readsCachedAssignmentsAndCourseSyncError() = runBlocking {
        withTimeout(5_000) {
            val courses = frontend.observeCourses().first()
            val unavailableCourse = courses.first { it.sync.state == SyncState.FAILED }
            val assignments = frontend.observeAssignments(unavailableCourse.id).first()
            assertEquals(unavailableCourse.id, assignments.single().courseId)
            assertNull(requireNotNull(assignments.single().dueAt).time)

            val sync = frontend.observeCourseSyncStatus(unavailableCourse.id).first()
            assertEquals(SyncState.FAILED, sync.state)
            assertEquals(ApiErrorCode.SOURCE_UNAVAILABLE, sync.errorCode)
        }
    }

    @Test
    fun manualRefreshExposesPartialResultByJobId() = runBlocking {
        withTimeout(5_000) {
            val jobId = requireSuccess(frontend.refresh())
            val finished = frontend.observeRefresh(jobId).first { it.state == JobStatus.PARTIAL }
            assertEquals(jobId, finished.jobId)
            assertEquals(ApiErrorCode.SOURCE_UNAVAILABLE, finished.errorCode)
        }
    }

    @Test
    fun cancelledCourseRefreshCanBeRetriedWithANewJobId() = runBlocking {
        withTimeout(5_000) {
            val courseId = frontend.observeCourses().first().first { it.syncEnabled }.id
            val originalJob = requireSuccess(frontend.refresh(listOf(courseId)))
            val running = frontend.observeRefresh(originalJob).first()
            assertEquals(JobStatus.RUNNING, running.state)

            assertEquals(ApiResult.Success(Unit), frontend.cancelRefresh(originalJob))
            val cancelled = frontend.observeRefresh(originalJob).first()
            assertEquals(JobStatus.CANCELLED, cancelled.state)
            assertEquals(ApiErrorCode.CANCELLED, cancelled.errorCode)

            val retryJob = requireSuccess(frontend.retryRefresh(originalJob))
            assertNotEquals(originalJob, retryJob)
            val finished = frontend.observeRefresh(retryJob).first { it.state == JobStatus.SUCCEEDED }
            assertEquals(retryJob, finished.jobId)
        }
    }

    private fun <T> requireSuccess(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.data
        is ApiResult.Failure -> throw AssertionError("Request failed with ${result.code}")
    }
}
