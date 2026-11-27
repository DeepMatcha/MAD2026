package org.deepmatcha.mad.backend.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.deepmatcha.mad.backend.school.AssignmentCheckpoint
import org.deepmatcha.mad.backend.school.AssignmentDelta
import org.deepmatcha.mad.backend.school.LocalSchoolRepository
import org.deepmatcha.mad.backend.school.SchoolAssignmentRecord
import org.deepmatcha.mad.backend.school.SchoolCourseRecord
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.applyAssignmentDelta
import org.deepmatcha.mad.contract.AIMode
import org.deepmatcha.mad.contract.AIReadiness
import org.deepmatcha.mad.contract.AnalysisState
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.ExtractedItemPatch
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.ManualReviewDecision
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.ReviewAction
import org.deepmatcha.mad.contract.ReviewReason
import org.deepmatcha.mad.contract.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAnalysisServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `mode and encrypted server settings survive reopening without contacting a disabled route`() = integration {
        val file = temp.root.resolve("analysis.json")
        val store = seededStore(file)
        val resourceId = LocalSchoolRepository(store).observeAssignments().first().single().id
        val calls = AtomicInteger()
        val personal = PersonalServerClient { uri, credential, body ->
            calls.incrementAndGet()
            assertEquals("https://ai.example.test/v1/models", uri.toString())
            assertEquals(FIXTURE_CREDENTIAL, credential)
            assertNull(body)
            ApiResult.Success(PersonalServerResponse(200, """{"data":[{"id":"fixture-model"}]}"""))
        }
        val configured = service(store, personal)
        assertEquals(AIReadiness.DISABLED, configured.getAIConfig().success().readiness)
        assertEquals(ApiErrorCode.UNSUPPORTED, configured.analyzeResource(resourceId).failureCode())
        assertEquals(ApiErrorCode.UNSUPPORTED, configured.testAIConnection().success().errorCode)

        configured.setAIMode(AIMode.PERSONAL_SERVER).success()
        assertEquals(AIReadiness.NEEDS_SETUP, configured.getAIConfig().success().readiness)
        configured.setAIConfig("https://ai.example.test", FIXTURE_CREDENTIAL, "fixture-model").success()
        assertEquals(AIReadiness.CONFIGURED, configured.getAIConfig().success().readiness)
        assertTrue(configured.testAIConnection().success().connected)
        assertEquals(AIReadiness.CONNECTED, configured.getAIConfig().success().readiness)
        assertTrue(file.readText().contains(FIXTURE_CIPHERTEXT))
        assertFalse(file.readText().contains(FIXTURE_CREDENTIAL))

        configured.setAIMode(AIMode.CHATGPT_SUBSCRIPTION).success()
        val reopened = service(SchoolStore(file), personal)
        val disconnected = reopened.getAIConfig().success()
        assertEquals(AIMode.CHATGPT_SUBSCRIPTION, disconnected.mode)
        assertEquals(AIReadiness.UNAVAILABLE, disconnected.readiness)
        assertNull(disconnected.baseUrl)
        assertEquals(ApiErrorCode.UNSUPPORTED, reopened.analyzeResource(resourceId).failureCode())
        assertEquals(ApiErrorCode.UNSUPPORTED, reopened.testAIConnection().success().errorCode)
        reopened.setAIMode(AIMode.DISABLED).success()
        assertEquals(ApiErrorCode.UNSUPPORTED, reopened.analyzeResource(resourceId).failureCode())
        assertEquals(1, calls.get())

        reopened.setAIMode(AIMode.PERSONAL_SERVER).success()
        val restored = service(SchoolStore(file), personal)
        assertEquals("fixture-model", restored.getAIConfig().success().modelId)
        assertEquals(AIReadiness.CONFIGURED, restored.getAIConfig().success().readiness)
        assertTrue(restored.testAIConnection().success().connected)
        assertEquals(2, calls.get())
    }

    @Test
    fun `personal server extracts a dated evidence candidate and review edits only the candidate`() = integration {
        val store = seededStore(temp.root.resolve("school.json"))
        val school = LocalSchoolRepository(store)
        val assignment = school.observeAssignments().first().single()
        val personal = PersonalServerClient { uri, credential, body ->
            assertEquals("https://ai.example.test/v1/chat/completions", uri.toString())
            assertEquals("", credential)
            val prompt = JsonParser.parseString(requireNotNull(body)).asJsonObject.getAsJsonArray("messages")[0]
                .asJsonObject.get("content").asString
            assertTrue(prompt.contains(assignment.title))
            assertTrue(prompt.contains(SOURCE_TEXT))
            assertFalse(prompt.contains(SOURCE_URL))
            completion(EXTRACTED_JSON)
        }
        val analysis = service(store, personal)
        analysis.setAIConfig("https://ai.example.test", "", "fixture-model").success()
        val jobId = analysis.analyzeResource(assignment.id).success()
        assertEquals(JobStatus.SUCCEEDED, analysis.awaitTerminal(jobId))
        val result = analysis.getAnalysisResult(jobId).success()
        assertEquals(AnalysisState.MANUAL_REVIEW, result.state)
        assertEquals(assignment.id, result.resourceId)
        assertEquals(CLOCK.instant(), result.analyzedAt)
        val candidate = result.candidates.single()
        assertEquals(KstMoment(LocalDate.of(2026, 12, 8), null), candidate.dueAt)
        assertNull(candidate.availableAt)
        assertNull(candidate.submissionFormat)
        assertEquals(SOURCE_TEXT, candidate.evidence.single().quote)
        assertEquals(SOURCE_URL, candidate.evidence.single().sourceUrl)

        val review = analysis.observeManualReviews().first().single()
        assertEquals(ReviewReason.NEEDS_CONFIRMATION, review.reason)
        val patch = ExtractedItemPatch(title = PatchField.Set("Edited suggestion"))
        analysis.resolveManualReview(review.id, ManualReviewDecision(ReviewAction.EDIT, patch)).success()
        val edited = analysis.observeManualReviews(ReviewStatus.EDITED).first().single()
        assertEquals("Edited suggestion", edited.candidates.single().title)
        assertEquals(assignment, school.getAssignment(assignment.id).success())
        assertTrue(analysis.observeManualReviews().first().isEmpty())
        assertEquals(
            ApiErrorCode.CONFLICT,
            analysis.resolveManualReview(review.id, ManualReviewDecision(ReviewAction.APPROVE)).failureCode(),
        )

        val secondJob = analysis.analyzeResource(assignment.id).success()
        assertEquals(JobStatus.SUCCEEDED, analysis.awaitTerminal(secondJob))
        val ready = analysis.observeManualReviews().first().single()
        analysis.resolveManualReview(ready.id, ManualReviewDecision(ReviewAction.APPROVE)).success()
        assertEquals(ready.id, analysis.observeManualReviews(ReviewStatus.APPROVED).first().single().id)
        assertEquals(assignment, school.getAssignment(assignment.id).success())
    }

    @Test
    fun `on-device analysis rejects invented evidence without contacting personal server`() = integration {
        val store = seededStore(temp.root.resolve("school.json"))
        val resourceId = LocalSchoolRepository(store).observeAssignments().first().single().id
        val personal = PersonalServerClient { _, _, _ -> error("The personal server should not be contacted") }
        val withoutEngine = service(store, personal)
        withoutEngine.setAIMode(AIMode.ON_DEVICE).success()
        assertEquals(AIReadiness.UNAVAILABLE, withoutEngine.getAIConfig().success().readiness)
        assertEquals(ApiErrorCode.UNSUPPORTED, withoutEngine.analyzeResource(resourceId).failureCode())

        val engine = object : OnDeviceTextEngine {
            override val modelId = "fixture-device"

            override suspend fun generate(prompt: String): ApiResult<String> = if (prompt.contains(SOURCE_TEXT)) {
                ApiResult.Success(EXTRACTED_JSON.replace(SOURCE_TEXT, "A quote absent from the source"))
            } else {
                ApiResult.Success("OK")
            }
        }
        val analysis = service(store, personal, device = engine)
        assertEquals(AIReadiness.CONFIGURED, analysis.getAIConfig().success().readiness)
        assertTrue(analysis.testAIConnection().success().connected)
        assertEquals("fixture-device", analysis.getAIConfig().success().modelId)
        val jobId = analysis.analyzeResource(resourceId).success()
        assertEquals(JobStatus.SUCCEEDED, analysis.awaitTerminal(jobId))
        assertTrue(analysis.getAnalysisResult(jobId).success().evidence.isEmpty())
        assertEquals(ReviewReason.INCOMPLETE, analysis.observeManualReviews().first().single().reason)
    }

    @Test
    fun `switching mode cancels an active job and ignores a provider response after cancellation`() = integration {
        val store = seededStore(temp.root.resolve("school.json"))
        val resourceId = LocalSchoolRepository(store).observeAssignments().first().single().id
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val personal = PersonalServerClient { _, _, _ ->
            started.complete(Unit)
            try {
                withContext(NonCancellable) { released.await() }
                completion(EXTRACTED_JSON)
            } finally {
                returned.complete(Unit)
            }
        }
        val analysis = service(store, personal)
        analysis.setAIConfig("https://ai.example.test", "", "fixture-model").success()
        val jobId = analysis.analyzeResource(resourceId).success()
        try {
            started.await()
            assertEquals(JobStatus.RUNNING, analysis.observeAnalysisJob(jobId).first().state)
            analysis.setAIMode(AIMode.DISABLED).success()
            assertEquals(JobStatus.CANCELLED, analysis.awaitTerminal(jobId))
        } finally {
            released.complete(Unit)
        }
        returned.await()
        assertEquals(ApiErrorCode.NOT_FOUND, analysis.getAnalysisResult(jobId).failureCode())
        assertTrue(analysis.observeManualReviews().first().isEmpty())

        val reopened = service(SchoolStore(temp.root.resolve("school.json")), personal)
        assertEquals(JobStatus.CANCELLED, reopened.awaitTerminal(jobId))
        assertEquals(AIMode.DISABLED, reopened.getAIConfig().success().mode)
    }

    private fun integration(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000, block)
    }

    private fun CoroutineScope.service(
        store: SchoolStore,
        personal: PersonalServerClient,
        device: OnDeviceTextEngine? = null,
    ) = LocalAnalysisService(store, LocalSchoolRepository(store), this, CLOCK, device, personal, FixtureSecrets)

    private suspend fun LocalAnalysisService.awaitTerminal(jobId: String): JobStatus =
        observeAnalysisJob(jobId).first { it.state !in setOf(JobStatus.QUEUED, JobStatus.RUNNING) }.state

    private suspend fun seededStore(file: File): SchoolStore = SchoolStore(file).apply {
        upsertCourses(listOf(SchoolCourseRecord("fixture-course", "Fixture Course"))).success()
        val courseId = state.value.courses.keys.single()
        val record = SchoolAssignmentRecord(
            externalId = "fixture-assignment",
            url = SOURCE_URL,
            remoteUpdatedAt = null,
            title = "Fixture Assignment",
            description = SOURCE_TEXT,
            submissionFormat = null,
            submissionMethod = null,
            gradingCriteria = null,
            availableAt = null,
            dueAt = null,
            schoolCompleted = false,
        )
        val delta = AssignmentDelta(listOf(record), emptySet(), AssignmentCheckpoint("fixture"))
        update { it.applyAssignmentDelta(courseId, null, delta, CLOCK.instant()) }.success()

        // Older snapshots have no AI data. Exercise the actual reader with that shape.
        val snapshot = JsonParser.parseString(file.readText()).asJsonObject
        snapshot.getAsJsonObject("state").remove("analysis")
        file.writeText(snapshot.toString())
    }.let { SchoolStore(file) }

    private fun completion(output: String): ApiResult<PersonalServerResponse> {
        val message = JsonObject().apply { addProperty("content", output) }
        val body = """{"choices":[{"finish_reason":"stop","message":$message}]}"""
        return ApiResult.Success(PersonalServerResponse(200, body))
    }

    private fun <T> ApiResult<T>.success(): T = (this as ApiResult.Success<T>).data

    private fun ApiResult<*>.failureCode(): ApiErrorCode = (this as ApiResult.Failure).code

    private object FixtureSecrets : AnalysisSecrets {
        override fun seal(value: String): String {
            assertEquals(FIXTURE_CREDENTIAL, value)
            return FIXTURE_CIPHERTEXT
        }

        override fun unseal(encrypted: String): String {
            assertEquals(FIXTURE_CIPHERTEXT, encrypted)
            return FIXTURE_CREDENTIAL
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC)
        const val FIXTURE_CREDENTIAL = "fixture-bearer"
        const val FIXTURE_CIPHERTEXT = "sealed-test-fixture"
        const val SOURCE_URL = "https://course.example.test/fixture"
        const val SOURCE_TEXT = "Submit the sample report by 2026-12-08."
        const val EXTRACTED_JSON = """{"kind":"TODO","title":"Sample report","dueAt":"2026-12-08","quote":"$SOURCE_TEXT"}"""
    }
}
