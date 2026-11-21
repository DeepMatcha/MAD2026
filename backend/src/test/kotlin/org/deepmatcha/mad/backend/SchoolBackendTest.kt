package org.deepmatcha.mad.backend

import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.deepmatcha.mad.backend.school.AssignmentCheckpoint
import org.deepmatcha.mad.backend.school.AssignmentDelta
import org.deepmatcha.mad.backend.school.SchoolAssignmentRecord
import org.deepmatcha.mad.backend.school.SchoolAssignmentSource
import org.deepmatcha.mad.backend.school.SchoolCourseRecord
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.AppSettingsPatch
import org.deepmatcha.mad.contract.CalendarItemPatch
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.MadDependencies
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.demo.DemoMadDependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SchoolBackendTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun partialSyncPreservesFailedCourseCacheAndRetriesOnlyItsLastCheckpoint() = backendTest {
        val source = ScriptedSource()
        withBackend(seededFile("A", "B", "C"), source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            assertSuccess(backend.school.setCourseSyncEnabled(ids.getValue("C"), false))

            source.complete("A", "a1", assignment(title = "Original A"))
            source.complete("B", "b1", assignment(title = "Original B"))
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            assertEquals(setOf(Fetch("A", null), Fetch("B", null)), source.takeCalls().toSet())
            val cachedB = frontend.observeAssignments(ids.getValue("B")).first().single()
            val successfulB = frontend.observeCourseSyncStatus(ids.getValue("B")).first()
            assertEquals(SyncState.SUCCESS, successfulB.state)

            source.complete("A", "a2", assignment(title = "Changed A"))
            source.respond("B") { ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true) }
            val partial = frontend.refreshAndAwait(JobStatus.PARTIAL)
            assertEquals(ApiErrorCode.NETWORK, partial.errorCode)
            assertEquals(setOf(Fetch("A", "a1"), Fetch("B", "b1")), source.takeCalls().toSet())
            assertEquals("Changed A", frontend.observeAssignments(ids.getValue("A")).first().single().title)
            assertEquals(cachedB, frontend.observeAssignments(ids.getValue("B")).first().single())
            val failedB = frontend.observeCourseSyncStatus(ids.getValue("B")).first()
            assertEquals(SyncState.FAILED, failedB.state)
            assertEquals(ApiErrorCode.NETWORK, failedB.errorCode)
            assertEquals(successfulB.lastSuccessAt, failedB.lastSuccessAt)
            assertEquals(SyncState.PARTIAL_SUCCESS, backend.sync.observeSyncStatus(Provider.SCHOOL).first().state)

            source.complete("B", "b2", assignment(title = "Recovered B"))
            val retryId = assertSuccess(frontend.retryRefresh(partial.jobId))
            assertNotEquals(partial.jobId, retryId)
            frontend.await(retryId, JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("B", "b1")), source.takeCalls())
            val recoveredB = frontend.observeAssignments(ids.getValue("B")).first().single()
            assertEquals(cachedB.id, recoveredB.id)
            assertEquals("Recovered B", recoveredB.title)

            source.respond("B") { ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true) }
            val failed = frontend.refreshAndAwait(JobStatus.FAILED, listOf(ids.getValue("B")))
            assertEquals(listOf(Fetch("B", "b2")), source.takeCalls())
            source.complete("B", "b3")
            frontend.await(assertSuccess(frontend.retryRefresh(failed.jobId)), JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("B", "b2")), source.takeCalls())
        }
    }

    @Test
    fun retryLeavesDisabledCoursesAvailableForRetryAfterReenable() = backendTest {
        val source = ScriptedSource()
        val file = seededFile("A", "B")
        withBackend(file, source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            source.respond("A") { ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true) }
            source.respond("B") { ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true) }
            val failed = frontend.refreshAndAwait(JobStatus.FAILED)
            assertEquals(setOf(Fetch("A", null), Fetch("B", null)), source.takeCalls().toSet())

            assertSuccess(backend.school.setCourseSyncEnabled(ids.getValue("B"), false))
            source.complete("A", "a1", assignment())
            frontend.await(assertSuccess(frontend.retryRefresh(failed.jobId)), JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("A", null)), source.takeCalls())

            assertSuccess(backend.school.setCourseSyncEnabled(ids.getValue("B"), true))
            source.complete("B", "b1", assignment())
            frontend.await(assertSuccess(frontend.retryRefresh(failed.jobId)), JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("B", null)), source.takeCalls())
        }
    }

    @Test
    fun cancelAfterOneCourseCommitsAndRetryOnlyTheInterruptedCourse() = backendTest {
        val source = ScriptedSource()
        withBackend(seededFile("A", "B"), source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            source.complete("A", "a1", assignment())
            source.complete("B", "b1", assignment())
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            source.takeCalls()
            val cachedB = frontend.observeAssignments(ids.getValue("B")).first().single()
            val lastSuccessB = frontend.observeCourseSyncStatus(ids.getValue("B")).first().lastSuccessAt

            source.complete("A", "a2", assignment(title = "Committed before cancel"))
            val fetchingB = source.suspendFetch("B")
            val jobId = assertSuccess(frontend.refresh(listOf(ids.getValue("A"), ids.getValue("B"))))
            fetchingB.await()
            frontend.observeAssignments(ids.getValue("A")).first { it.single().title == "Committed before cancel" }
            assertSuccess(frontend.cancelRefresh(jobId))
            val cancelled = frontend.await(jobId, JobStatus.CANCELLED)
            assertEquals(ApiErrorCode.CANCELLED, cancelled.errorCode)
            assertEquals(setOf(Fetch("A", "a1"), Fetch("B", "b1")), source.takeCalls().toSet())
            assertEquals(cachedB, frontend.observeAssignments(ids.getValue("B")).first().single())
            assertEquals(lastSuccessB, frontend.observeCourseSyncStatus(ids.getValue("B")).first().lastSuccessAt)

            source.complete("B", "b2", assignment(title = "Recovered after cancel"))
            frontend.await(assertSuccess(frontend.retryRefresh(jobId)), JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("B", "b1")), source.takeCalls())
            assertEquals("Recovered after cancel", frontend.observeAssignments(ids.getValue("B")).first().single().title)
        }
    }

    @Test
    fun persistedInFlightJobCanBeRetriedAfterOpeningStoreInANewScope() = backendTest {
        val source = ScriptedSource()
        val file = seededFile("A", "B")
        val recoveredFile = temp.root.resolve("interrupted-school.json")
        val jobId = withBackend(file, source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            source.complete("A", "a1", assignment())
            val fetchingB = source.suspendFetch("B")
            val running = assertSuccess(frontend.refresh(listOf(ids.getValue("A"), ids.getValue("B"))))
            fetchingB.await()
            frontend.observeAssignments(ids.getValue("A")).first { it.size == 1 }
            assertEquals(JobStatus.RUNNING, frontend.observeRefresh(running).first().state)

            // Preserve the durable state as it was before the process had a chance to clean up.
            file.copyTo(recoveredFile)
            running
        }
        assertEquals(setOf(Fetch("A", null), Fetch("B", null)), source.takeCalls().toSet())

        withBackend(recoveredFile, source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            assertEquals(1, frontend.observeAssignments(ids.getValue("A")).first().size)
            assertEquals(JobStatus.CANCELLED, frontend.observeRefresh(jobId).first().state)
            assertTrue(source.takeCalls().isEmpty())
            source.complete("B", "b1", assignment())
            frontend.await(assertSuccess(frontend.retryRefresh(jobId)), JobStatus.SUCCEEDED)
            assertEquals(listOf(Fetch("B", null)), source.takeCalls())
            source.complete("A", "a2")
            frontend.refreshAndAwait(JobStatus.SUCCEEDED, listOf(ids.getValue("A")))
            assertEquals(listOf(Fetch("A", "a1")), source.takeCalls())
        }
    }

    @Test
    fun incrementalOmissionKeepsAssignmentsAndConfirmedDeletionIsCourseScoped() = backendTest {
        val source = ScriptedSource()
        withBackend(seededFile("A", "B"), source) { backend ->
            val frontend = FrontendStub(backend)
            val ids = frontend.courseIds()
            source.complete("A", "a1", assignment())
            source.complete("B", "b1", assignment())
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            val assignmentA = frontend.observeAssignments(ids.getValue("A")).first().single()
            val assignmentB = frontend.observeAssignments(ids.getValue("B")).first().single()
            assertNotEquals(assignmentA.id, assignmentB.id)
            assertEquals(assignmentA.source?.externalId, assignmentB.source?.externalId)

            source.complete("A", "a2")
            frontend.refreshAndAwait(JobStatus.SUCCEEDED, listOf(ids.getValue("A")))
            assertEquals(assignmentA, assertSuccess(backend.school.getAssignment(assignmentA.id)))

            source.complete("A", "a3", assignment(dueAt = date(2)))
            frontend.refreshAndAwait(JobStatus.SUCCEEDED, listOf(ids.getValue("A")))
            assertEquals(assignmentA.id, backend.calendar.observePendingChanges().first().single().itemId)
            source.complete("A", "a4", deleted = setOf("shared-assignment"))
            frontend.refreshAndAwait(JobStatus.SUCCEEDED, listOf(ids.getValue("A")))
            assertTrue(frontend.observeAssignments(ids.getValue("A")).first().isEmpty())
            assertTrue(backend.calendar.observePendingChanges().first().isEmpty())
            assertFailure(ApiErrorCode.NOT_FOUND, backend.school.getAssignment(assignmentA.id))
            assertEquals(assignmentB, assertSuccess(backend.school.getAssignment(assignmentB.id)))
        }
    }

    @Test
    fun reimportAndReopenPreserveUserOverridesAndKeepDatesPendingUntilApproved() = backendTest {
        val source = ScriptedSource()
        val file = seededFile("A")
        val assignmentId = withBackend(file, source) { backend ->
            val frontend = FrontendStub(backend)
            val courseId = frontend.courseIds().getValue("A")
            source.complete("A", "a1", assignment())
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            val original = frontend.observeAssignments(courseId).first().single()
            UUID.fromString(original.id)
            assertNull(original.submissionFormat)
            assertNull(original.dueAt?.time)

            val calendarId = assertSuccess(backend.calendar.getCalendarItem(original.id)).id
            val localEdit = CalendarItemPatch(
                title = PatchField.Set("My edited title"),
                description = PatchField.Set(null),
            )
            assertSuccess(backend.calendar.updateCalendarItem(calendarId, localEdit))
            assertSuccess(backend.school.setUserCompleted(original.id, true))
            assertSuccess(backend.school.setSchoolItemHidden(original.id, true))

            val changed = assignment(title = "New source title", dueAt = date(2), schoolCompleted = true)
            source.complete("A", "a2", changed)
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            val combined = assertSuccess(backend.school.getAssignment(original.id))
            assertEquals("My edited title", combined.title)
            assertNull(combined.description)
            assertTrue(combined.userCompleted)
            assertTrue(combined.schoolCompleted)
            assertTrue(combined.hidden)
            assertEquals(date(1), combined.dueAt)
            assertEquals(date(1), assertSuccess(backend.calendar.getCalendarItem(calendarId)).startAt)
            assertTrue(frontend.observeAssignments(courseId).first().isEmpty())
            assertTrue(backend.school.observeAssignments(includeHidden = true, includeCompleted = false).first().isEmpty())

            // A school completion alone still counts when the user's completion is cleared.
            assertTrue(assertSuccess(backend.school.setUserCompleted(original.id, false)).completed)
            assertSuccess(backend.school.setUserCompleted(original.id, true))
            val proposed = backend.calendar.observePendingChanges().first().single()
            assertEquals(original.id, proposed.itemId)
            assertSuccess(backend.calendar.resolvePendingChange(proposed.id, approve = false))

            source.complete("A", "a3", changed.copy(schoolCompleted = false))
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            assertTrue(backend.calendar.observePendingChanges().first().isEmpty())
            val userOnly = assertSuccess(backend.school.getAssignment(original.id))
            assertFalse(userOnly.schoolCompleted)
            assertTrue(userOnly.completed)
            assertEquals(date(1), userOnly.dueAt)

            source.complete("A", "a4", changed.copy(dueAt = date(3), schoolCompleted = false))
            frontend.refreshAndAwait(JobStatus.SUCCEEDED)
            original.id
        }

        withBackend(file, source) { backend ->
            val beforeApproval = assertSuccess(backend.school.getAssignment(assignmentId))
            assertEquals("My edited title", beforeApproval.title)
            assertNull(beforeApproval.description)
            assertTrue(beforeApproval.userCompleted)
            assertTrue(beforeApproval.hidden)
            assertEquals(date(1), beforeApproval.dueAt)
            val pending = backend.calendar.observePendingChanges().first().single()
            assertEquals(assignmentId, pending.itemId)
            assertSuccess(backend.calendar.resolvePendingChange(pending.id, approve = true))
            assertEquals(date(3), assertSuccess(backend.school.getAssignment(assignmentId)).dueAt)
            assertEquals(date(3), assertSuccess(backend.calendar.getCalendarItem(assignmentId)).startAt)
            assertTrue(backend.calendar.observePendingChanges().first().isEmpty())

            val ownDate = CalendarItemPatch(startAt = PatchField.Set(date(4)))
            assertSuccess(backend.calendar.updateCalendarItem(assignmentId, ownDate))
            source.complete("A", "a5", assignment(dueAt = date(5)))
            FrontendStub(backend).refreshAndAwait(JobStatus.SUCCEEDED)
            val nextChange = backend.calendar.observePendingChanges().first().single()
            assertEquals(date(4).date.toString(), nextChange.oldValue)
            assertSuccess(backend.calendar.resolvePendingChange(nextChange.id, approve = true))
            assertEquals(date(4), assertSuccess(backend.school.getAssignment(assignmentId)).dueAt)
        }
    }

    @Test
    fun openingAndObservingUseCacheAndScheduleAtTheConfiguredInterval() = backendTest {
        val source = ScriptedSource()
        val intervals = mutableListOf<Int>()
        val file = seededFile("A")
        withBackend(file, source, schedule = { intervals.add(it) }) { backend ->
            val frontend = FrontendStub(backend)
            val courseId = frontend.courseIds().getValue("A")
            assertTrue(frontend.observeAssignments(courseId).first().isEmpty())
            assertEquals(SyncState.IDLE, frontend.observeCourseSyncStatus(courseId).first().state)
            assertEquals(listOf(60), intervals)
            assertEquals(60, backend.settings.observeSettings().first().syncIntervalMinutes)
            val valid = AppSettingsPatch(syncIntervalMinutes = PatchField.Set(15))
            assertEquals(15, assertSuccess(backend.settings.updateSettings(valid)).syncIntervalMinutes)
            val invalid = AppSettingsPatch(syncIntervalMinutes = PatchField.Set(14))
            assertFailure(ApiErrorCode.INVALID_ARGUMENT, backend.settings.updateSettings(invalid))
            assertEquals(listOf(60, 15), intervals)
            assertTrue(source.takeCalls().isEmpty())
        }
        withBackend(file, source, schedule = { intervals.add(it) }) { backend ->
            assertEquals(15, backend.settings.observeSettings().first().syncIntervalMinutes)
            assertEquals(listOf(60, 15, 15), intervals)
            assertTrue(source.takeCalls().isEmpty())
        }
    }

    private fun backendTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000, block)
    }

    private suspend fun seededFile(vararg courseIds: String): File {
        val file = temp.root.resolve("school.json")
        val courses = courseIds.map { SchoolCourseRecord(externalId = it, name = "Synthetic $it") }
        assertSuccess(SchoolStore(file).upsertCourses(courses))
        return file
    }

    private suspend fun <T> CoroutineScope.withBackend(
        file: File,
        source: SchoolAssignmentSource,
        schedule: suspend (Int) -> Unit = {},
        block: suspend (MadDependencies) -> T,
    ): T {
        val supervisor = SupervisorJob(coroutineContext[Job])
        try {
            val backend = SchoolBackend.open(file, source, CoroutineScope(coroutineContext + supervisor), schedule = schedule)
            val dependencies = object : MadDependencies by DemoMadDependencies.create() {
                override val school = backend.school
                override val calendar = backend.calendar
                override val sync = backend.sync
                override val settings = backend.settings
            }
            return block(dependencies)
        } finally {
            supervisor.cancelAndJoin()
        }
    }

    private suspend fun FrontendStub.courseIds() = observeCourses().first()
        .associate { requireNotNull(it.source.externalId) to it.id }

    private suspend fun FrontendStub.refreshAndAwait(expected: JobStatus, courseIds: List<String>? = null) =
        await(assertSuccess(refresh(courseIds)), expected)

    private suspend fun FrontendStub.await(jobId: String, expected: JobStatus) =
        observeRefresh(jobId).first { it.state != JobStatus.QUEUED && it.state != JobStatus.RUNNING }
            .also { assertEquals("Unexpected terminal job state: $it", expected, it.state) }

    private fun <T> assertSuccess(result: ApiResult<T>): T = when (result) {
        is ApiResult.Success -> result.data
        is ApiResult.Failure -> throw AssertionError("Request failed with ${result.code}")
    }

    private fun assertFailure(code: ApiErrorCode, result: ApiResult<*>) {
        assertEquals(code, (result as? ApiResult.Failure)?.code)
    }

    private fun assignment(
        title: String = "Synthetic assignment",
        dueAt: KstMoment = date(1),
        schoolCompleted: Boolean = false,
    ) = SchoolAssignmentRecord(
        externalId = "shared-assignment",
        url = "https://school.example/assignments/shared",
        remoteUpdatedAt = null,
        title = title,
        description = "Synthetic source description",
        submissionFormat = null,
        submissionMethod = null,
        gradingCriteria = null,
        availableAt = null,
        dueAt = dueAt,
        schoolCompleted = schoolCompleted,
    )

    private fun date(day: Int) = KstMoment(LocalDate.of(2026, 12, day), time = null)

    private data class Fetch(val courseId: String, val checkpoint: String?)

    private class ScriptedSource : SchoolAssignmentSource {
        private val calls = ConcurrentLinkedQueue<Fetch>()
        private val responses =
            ConcurrentHashMap<String, ConcurrentLinkedQueue<suspend () -> ApiResult<AssignmentDelta>>>()

        fun takeCalls() = generateSequence { calls.poll() }.toList()

        fun respond(courseId: String, response: suspend () -> ApiResult<AssignmentDelta>) {
            responses.computeIfAbsent(courseId) { ConcurrentLinkedQueue() }.add(response)
        }

        fun complete(
            courseId: String,
            checkpoint: String,
            vararg records: SchoolAssignmentRecord,
            deleted: Set<String> = emptySet(),
        ) = respond(courseId) {
            ApiResult.Success(AssignmentDelta(records.toList(), deleted, AssignmentCheckpoint(checkpoint)))
        }

        fun suspendFetch(courseId: String): CompletableDeferred<Unit> {
            val entered = CompletableDeferred<Unit>()
            respond(courseId) {
                entered.complete(Unit)
                awaitCancellation()
            }
            return entered
        }

        override suspend fun fetchChanges(externalCourseId: String, checkpoint: AssignmentCheckpoint?): ApiResult<AssignmentDelta> {
            calls.add(Fetch(externalCourseId, checkpoint?.value))
            val response = checkNotNull(responses[externalCourseId]?.poll()) { "Unexpected fetch for $externalCourseId" }
            return response()
        }
    }
}
