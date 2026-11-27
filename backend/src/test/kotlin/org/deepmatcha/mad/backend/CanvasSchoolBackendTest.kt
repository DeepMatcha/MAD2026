package org.deepmatcha.mad.backend

import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.canvas.CanvasClient
import org.deepmatcha.mad.backend.school.canvas.CanvasResponse
import org.deepmatcha.mad.backend.school.canvas.CanvasSchoolSource
import org.deepmatcha.mad.backend.school.canvas.CanvasTransport
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanvasSchoolBackendTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun discoveryIsExplicitAndAnIncompleteRosterPreservesTheLastActiveCourses() = backendTest {
        val transport = ScriptedCanvasTransport()
        val credentialRequests = AtomicInteger()
        var suppliedCredential: String? = null
        val client = CanvasClient(
            credential = {
                credentialRequests.incrementAndGet()
                suppliedCredential
            },
            transport = transport,
        )
        val file = temp.newFolder().resolve("eclass.json")
        withBackend(file, client) { backend ->
            assertTrue(backend.school.observeCourses().first().isEmpty())
            assertEquals(0, credentialRequests.get())
            assertFailure(ApiErrorCode.UNAUTHENTICATED, backend.refreshCourses())
            assertEquals(1, credentialRequests.get())
            assertEquals(0, transport.callCount.get())

            suppliedCredential = FIXTURE_CREDENTIAL
            transport.respond(COURSES_PATH, "[]", next = "$ORIGIN$COURSES_PATH?page=2")
            transport.respond(COURSES_PATH, """[{"id":1001,"name":"Synthetic course"}]""", page = 2)
            val original = assertSuccess(backend.refreshCourses()).single()
            assertEquals("1001", original.source.externalId)
            assertTrue(original.syncEnabled)
            assertEquals(2, transport.callCount.get())

            transport.respond(
                COURSES_PATH,
                """[{"id":1002,"name":"Another synthetic course"}]""",
                next = "$ORIGIN$COURSES_PATH?page=2",
            )
            transport.respond(COURSES_PATH, "[]", status = 429, page = 2)
            assertFailure(ApiErrorCode.RATE_LIMIT, backend.refreshCourses())
            assertEquals(listOf(original), backend.school.observeCourses().first())
        }

        val previousCalls = transport.callCount.get()
        val previousCredentialRequests = credentialRequests.get()
        withBackend(file, client) { backend ->
            val cached = backend.school.observeCourses().first().single()
            assertTrue(cached.syncEnabled)
            assertEquals(previousCalls, transport.callCount.get())
            assertEquals(previousCredentialRequests, credentialRequests.get())

            transport.respond(COURSES_PATH, "[]")
            assertTrue(assertSuccess(backend.refreshCourses()).isEmpty())
            assertEquals(cached.copy(syncEnabled = false), backend.school.observeCourses().first().single())
            backend.await(assertSuccess(backend.sync.startSync()), JobStatus.SUCCEEDED)
            assertEquals(previousCalls + 1, transport.callCount.get())
        }
    }

    @Test
    fun assignmentPaginationCommitsOnlyCompleteSnapshotsAndRetryPreservesLocalState() = backendTest {
        val transport = ScriptedCanvasTransport()
        val file = temp.newFolder().resolve("eclass.json")
        withBackend(file, CanvasClient(credential = { FIXTURE_CREDENTIAL }, transport = transport)) { backend ->
            transport.respond(COURSES_PATH, """[{"id":1001,"name":"Synthetic course"}]""")
            val course = assertSuccess(backend.refreshCourses()).single()

            transport.respond(
                ASSIGNMENTS_PATH,
                """[
                    {
                        "id":2001,
                        "name":"Initial assignment",
                        "due_at":"2026-12-02T18:30:00Z",
                        "unlock_at":"2026-12-01",
                        "submission":{"workflow_state":"submitted"}
                    }
                ]""",
                next = "$ORIGIN$ASSIGNMENTS_PATH?page=2",
            )
            transport.respond(
                ASSIGNMENTS_PATH,
                """[{"id":2002,"name":"Second assignment","submission":{"workflow_state":"graded"}}]""",
                page = 2,
            )
            val initial = backend.await(assertSuccess(backend.sync.startSync()), JobStatus.SUCCEEDED)
            assertNull(initial.errorCode)

            val original = backend.school.observeAssignments(course.id).first().associateBy { it.source?.externalId }
            val first = original.getValue("2001")
            val second = original.getValue("2002")
            assertEquals(KstMoment(LocalDate.of(2026, 12, 3), LocalTime.of(3, 30)), first.dueAt)
            assertEquals(KstMoment(LocalDate.of(2026, 12, 1), null), first.availableAt)
            assertTrue(first.schoolCompleted)
            assertFalse(second.schoolCompleted)
            assertNull(second.dueAt)
            assertNull(second.description)
            assertNull(second.gradingCriteria)
            assertSuccess(backend.school.setUserCompleted(first.id, true))

            val lastGood = backend.school.observeAssignments(course.id).first()
            val checkpoint = SchoolStore(file).readCheckpoint(course.id)
            assertNotNull(checkpoint)
            val lastSuccessAt = backend.sync.observeCourseSyncStatus(course.id).first().lastSuccessAt
            val changedFirst = """[{"id":2001,"name":"Changed assignment","due_at":"2026-12-02T18:30:00Z"}]"""

            transport.respond(ASSIGNMENTS_PATH, changedFirst, next = "$ORIGIN$ASSIGNMENTS_PATH?page=2")
            transport.respond(ASSIGNMENTS_PATH, "[]", status = 503, page = 2)
            val failed = backend.await(assertSuccess(backend.sync.startSync()), JobStatus.FAILED)
            assertNotNull(failed.errorCode)
            assertEquals(lastGood, backend.school.observeAssignments(course.id).first())
            assertEquals(checkpoint, SchoolStore(file).readCheckpoint(course.id))
            val failedCourse = backend.sync.observeCourseSyncStatus(course.id).first()
            assertEquals(SyncState.FAILED, failedCourse.state)
            assertEquals(lastSuccessAt, failedCourse.lastSuccessAt)

            transport.respond(
                ASSIGNMENTS_PATH,
                changedFirst,
                next = "$ORIGIN$ASSIGNMENTS_PATH?page=2",
            )
            transport.respond(
                ASSIGNMENTS_PATH,
                """[{"id":2003,"name":"Added assignment","due_at":"2026-12-08"}]""",
                page = 2,
            )
            backend.await(assertSuccess(backend.sync.retrySync(failed.jobId)), JobStatus.SUCCEEDED)
            val refreshed = backend.school.observeAssignments(course.id).first().associateBy { it.source?.externalId }
            assertEquals(setOf("2001", "2003"), refreshed.keys)
            assertFailure(ApiErrorCode.NOT_FOUND, backend.school.getAssignment(second.id))
            val changed = refreshed.getValue("2001")
            assertEquals(first.id, changed.id)
            assertEquals("Changed assignment", changed.title)
            assertTrue(changed.userCompleted)
            assertTrue(changed.completed)
            assertEquals(KstMoment(LocalDate.of(2026, 12, 8), null), refreshed.getValue("2003").dueAt)
            assertNotEquals(checkpoint, SchoolStore(file).readCheckpoint(course.id))
        }
    }

    private fun backendTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000, block)
    }

    private suspend fun <T> CoroutineScope.withBackend(
        file: File,
        client: CanvasClient,
        block: suspend (CanvasSchoolBackend) -> T,
    ): T {
        val supervisor = SupervisorJob(coroutineContext[Job])
        try {
            val backend = CanvasSchoolBackend.open(file, CoroutineScope(coroutineContext + supervisor), client)
            return block(backend)
        } finally {
            supervisor.cancelAndJoin()
        }
    }

    private suspend fun CanvasSchoolBackend.await(jobId: String, expected: JobStatus) =
        sync.observeSyncJob(jobId).first { it.state != JobStatus.QUEUED && it.state != JobStatus.RUNNING }
            .also { assertEquals("Unexpected terminal job state: $it", expected, it.state) }
}

class CanvasClientTest {
    @Test
    fun rejectsUntrustedPaginationBeforeTheCredentialLeavesTheCanvasOrigin() = runBlocking {
        val rejectedTargets = listOf(
            "https://fixture.invalid$COURSES_PATH?page=2",
            "http://eclass3.cau.ac.kr$COURSES_PATH?page=2",
            "$ORIGIN/login/session_token",
            "$ORIGIN/api/v1fake/courses?page=2",
            "https://eclass3.cau.ac.kr:444$COURSES_PATH?page=2",
            "$ORIGIN/api/v1/../login/session_token",
            "$ORIGIN$COURSES_PATH?page=2&access_token=fake",
        )
        for (target in rejectedTargets) {
            val transport = ScriptedCanvasTransport()
            transport.respond(COURSES_PATH, "[]", next = target)
            val client = CanvasClient(credential = { FIXTURE_CREDENTIAL }, transport = transport)

            assertFailure(ApiErrorCode.PARSE_FAILED, client.getList(COURSES_PATH))
            assertEquals(1, transport.callCount.get())
        }
    }

    @Test
    fun failsClosedOnPaginationLoopsAndRedirectResponses() = runBlocking {
        val transport = ScriptedCanvasTransport()
        val client = CanvasClient(credential = { FIXTURE_CREDENTIAL }, transport = transport)
        transport.respond(COURSES_PATH, "[]", next = "$ORIGIN$COURSES_PATH")
        assertFailure(ApiErrorCode.PARSE_FAILED, client.getList(COURSES_PATH))
        assertEquals(1, transport.callCount.get())

        transport.respond(COURSES_PATH, "", status = 302)
        assertFailure(ApiErrorCode.SOURCE_UNAVAILABLE, client.getList(COURSES_PATH))
        assertEquals(2, transport.callCount.get())
    }
}

class CanvasSchoolSourceTest {
    @Test
    fun unchangedSnapshotsSkipUpsertsAndDuplicateRecordsFailClosed() = runBlocking {
        val transport = ScriptedCanvasTransport()
        val source = CanvasSchoolSource(CanvasClient(credential = { FIXTURE_CREDENTIAL }, transport = transport))
        val assignment = """{"id":2001,"name":"Synthetic assignment","all_day":true,"all_day_date":"2026-12-08"}"""
        transport.respond(ASSIGNMENTS_PATH, "[$assignment]")
        val initial = assertSuccess(source.fetchChanges("1001", null))
        assertEquals(KstMoment(LocalDate.of(2026, 12, 8), null), initial.upserts.single().dueAt)

        transport.respond(ASSIGNMENTS_PATH, "[$assignment]")
        val unchanged = assertSuccess(source.fetchChanges("1001", initial.nextCheckpoint))
        assertTrue(unchanged.upserts.isEmpty())
        assertTrue(unchanged.confirmedDeletedExternalIds.isEmpty())
        assertEquals(initial.nextCheckpoint, unchanged.nextCheckpoint)

        transport.respond(ASSIGNMENTS_PATH, "[$assignment]", next = "$ORIGIN$ASSIGNMENTS_PATH?page=2")
        transport.respond(ASSIGNMENTS_PATH, "[$assignment]", page = 2)
        assertFailure(ApiErrorCode.PARSE_FAILED, source.fetchChanges("1001", initial.nextCheckpoint))
    }
}

private const val ORIGIN = "https://eclass3.cau.ac.kr"
private const val COURSES_PATH = "/api/v1/courses"
private const val ASSIGNMENTS_PATH = "/api/v1/courses/1001/assignments"
private const val FIXTURE_CREDENTIAL = "synthetic-value"

private class ScriptedCanvasTransport : CanvasTransport {
    private data class ExpectedRequest(val path: String, val page: Int?, val response: CanvasResponse)

    private val pending = ConcurrentLinkedQueue<ExpectedRequest>()
    val callCount = AtomicInteger()

    fun respond(path: String, body: String, status: Int = 200, page: Int? = null, next: String? = null) {
        val links = next?.let { listOf("<$it>; rel=\"next\"") }.orEmpty()
        pending.add(ExpectedRequest(path, page, CanvasResponse(status, body, links)))
    }

    override suspend fun get(url: URI, credential: String): CanvasResponse {
        callCount.incrementAndGet()
        val expected = checkNotNull(pending.poll()) { "Unexpected Canvas request" }
        assertEquals("https", url.scheme)
        assertEquals("eclass3.cau.ac.kr", url.host)
        assertEquals(FIXTURE_CREDENTIAL, credential)
        assertEquals(expected.path, url.path)
        if (expected.page != null) assertTrue("Expected next page", "page=${expected.page}" in url.query.orEmpty())
        return expected.response
    }
}

private fun <T> assertSuccess(result: ApiResult<T>): T = when (result) {
    is ApiResult.Success -> result.data
    is ApiResult.Failure -> throw AssertionError("Request failed with ${result.code}")
}

private fun assertFailure(code: ApiErrorCode, result: ApiResult<*>) {
    assertEquals(code, (result as? ApiResult.Failure)?.code)
}
