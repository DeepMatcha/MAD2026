package org.deepmatcha.mad.demo

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.deepmatcha.mad.contract.AIConfigPublic
import org.deepmatcha.mad.contract.AIMode
import org.deepmatcha.mad.contract.AIReadiness
import org.deepmatcha.mad.contract.AnalysisResult
import org.deepmatcha.mad.contract.AnalysisService
import org.deepmatcha.mad.contract.AnalysisState
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.AppSettingsPatch
import org.deepmatcha.mad.contract.AuthState
import org.deepmatcha.mad.contract.ConnectionTestResult
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.GoogleAuthResult
import org.deepmatcha.mad.contract.GoogleCalendarInfo
import org.deepmatcha.mad.contract.GoogleCalendarRepository
import org.deepmatcha.mad.contract.IntegrationStatus
import org.deepmatcha.mad.contract.IntegrationsService
import org.deepmatcha.mad.contract.JobKind
import org.deepmatcha.mad.contract.JobState
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.ManualReviewDecision
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.ReviewAction
import org.deepmatcha.mad.contract.ReviewStatus
import org.deepmatcha.mad.contract.SyncService

internal class DemoIntegrations(
    private val fixtures: DemoFixtures,
    private val local: DemoLocalData,
    private val preferences: DemoSettings,
) : IntegrationsService, SyncService, AnalysisService, GoogleCalendarRepository {
    private val integrations = listOf(
        IntegrationStatus(Provider.SCHOOL, true, AuthState.AUTHENTICATED, fixtures.partial),
        IntegrationStatus(Provider.GOOGLE_CALENDAR, true, AuthState.AUTHENTICATED, fixtures.successful),
        IntegrationStatus(Provider.AI, false, AuthState.DISCONNECTED, null),
    )
    private val google = GoogleCalendarInfo("demo-calendar", "개인 일정", primary = true, writable = true)
    private val syncJobs = MutableStateFlow(mapOf<String, SyncPreview>())
    private val aiSettings = MutableStateFlow(DemoAISettings())
    private val analysisResults = MutableStateFlow(mapOf<String, AnalysisResult>())
    private val reviews = MutableStateFlow(listOf(fixtures.review))

    override fun observeIntegrations() = flowOf(integrations)

    // Device authentication and writes to external services require a real implementation.
    override suspend fun connectSchool(studentId: String, password: String) = unsupported()
    override suspend fun refreshSchoolAuth() = unsupported()
    override suspend fun beginGoogleAuth() = unsupported()
    override suspend fun completeGoogleAuth(result: GoogleAuthResult) = unsupported()
    override suspend fun disconnectGoogle() = unsupported()

    override suspend fun testIntegration(provider: Provider) =
        if (provider == Provider.AI) testAIConnection()
        else ApiResult.Success(ConnectionTestResult(true, setOf("read"), null))

    override suspend fun startSync(provider: Provider, courseIds: List<String>?): ApiResult<String> {
        if (provider == Provider.AI) return unsupported()
        val id = newId()
        val count = courseIds?.size ?: fixtures.courses.count { it.syncEnabled }
        val state = if (provider == Provider.SCHOOL && count > 1) JobStatus.PARTIAL else JobStatus.SUCCEEDED
        val error = if (state == JobStatus.PARTIAL) ApiErrorCode.SOURCE_UNAVAILABLE else null
        val result = JobState(id, JobKind.SYNC, state, 1f, count, count, error)
        syncJobs.update { it + (id to SyncPreview(provider, courseIds, result)) }
        return ApiResult.Success(id)
    }

    override fun observeSyncJob(jobId: String) = flow {
        val result = syncJobs.value[jobId]?.state
        if (result != null && result.state != JobStatus.CANCELLED) {
            emit(result.copy(state = JobStatus.RUNNING, progress = 0.5f, processed = null, errorCode = null))
            delay(500)
        }
        emitAll(syncJobs.map {
            it[jobId]?.state ?: unknownJob(jobId, JobKind.SYNC)
        }.distinctUntilChanged())
    }

    override fun observeSyncStatus(provider: Provider) =
        flowOf(integrations.single { it.provider == provider }.sync ?: fixtures.idle)

    override fun observeCourseSyncStatus(courseId: String) =
        local.observeCourses().map { courses -> courses.find { it.id == courseId }?.sync ?: fixtures.idle }

    override suspend fun cancelSync(jobId: String): ApiResult<Unit> {
        val job = syncJobs.value[jobId] ?: return null.asResult()
        val cancelled = job.state.copy(state = JobStatus.CANCELLED, progress = null, errorCode = ApiErrorCode.CANCELLED)
        syncJobs.update { it + (jobId to job.copy(state = cancelled)) }
        return ApiResult.Success(Unit)
    }

    override suspend fun retrySync(jobId: String): ApiResult<String> {
        val job = syncJobs.value[jobId] ?: return null.asResult()
        return startSync(job.provider, job.courseIds)
    }

    override suspend fun getAIConfig() = ApiResult.Success(aiSettings.value.asPublicConfig())

    override suspend fun setAIMode(mode: AIMode): ApiResult<Unit> {
        aiSettings.update { it.copy(mode = mode) }
        return ApiResult.Success(Unit)
    }

    override suspend fun setAIConfig(baseUrl: String, apiKey: String, modelId: String?): ApiResult<Unit> {
        val endpoint = try {
            URI(baseUrl.trim())
        } catch (_: URISyntaxException) {
            return invalidArgument()
        }
        val scheme = endpoint.scheme?.lowercase()
        if (scheme !in setOf("https", "http") || endpoint.host == null || endpoint.rawUserInfo != null ||
            endpoint.rawQuery != null || endpoint.rawFragment != null || (scheme == "http" && apiKey.isNotBlank())
        ) return invalidArgument()

        aiSettings.value = DemoAISettings(
            mode = AIMode.PERSONAL_SERVER,
            personalServer = AIConfigPublic(
                baseUrl = endpoint.toString(),
                modelId = modelId?.trim()?.takeUnless(String::isBlank),
                configured = true,
                connected = false,
                mode = AIMode.PERSONAL_SERVER,
                readiness = AIReadiness.CONFIGURED,
            ),
        )
        return ApiResult.Success(Unit)
    }

    override suspend fun testAIConnection(): ApiResult<ConnectionTestResult> {
        val state = aiSettings.value
        if (state.mode != AIMode.PERSONAL_SERVER) {
            return ApiResult.Success(ConnectionTestResult(false, emptySet(), ApiErrorCode.UNSUPPORTED))
        }
        val server = state.personalServer
            ?: return ApiResult.Success(ConnectionTestResult(false, emptySet(), ApiErrorCode.INVALID_ARGUMENT))

        // The preview simulates a successful connection so the settings screen can display it.
        aiSettings.compareAndSet(
            state,
            state.copy(personalServer = server.copy(connected = true, readiness = AIReadiness.CONNECTED)),
        )
        return ApiResult.Success(ConnectionTestResult(true, setOf("models"), null))
    }

    override suspend fun analyzeResource(resourceId: String): ApiResult<String> {
        val state = aiSettings.value
        if (state.mode != AIMode.PERSONAL_SERVER) return unsupported()
        if (state.personalServer == null) return invalidArgument()
        if (fixtures.resources.none { it.id == resourceId }) return null.asResult()
        val jobId = newId()
        val result = AnalysisResult(
            resourceId, AnalysisState.MANUAL_REVIEW, listOf(fixtures.candidate), listOf(fixtures.evidence), Instant.now(),
        )
        analysisResults.update { it + (jobId to result) }
        return ApiResult.Success(jobId)
    }

    override fun observeAnalysisJob(jobId: String) = flow {
        if (jobId in analysisResults.value) {
            emit(JobState(jobId, JobKind.ANALYSIS, JobStatus.RUNNING, null, null, null, null))
            delay(500)
            emit(JobState(jobId, JobKind.ANALYSIS, JobStatus.SUCCEEDED, null, 1, 1, null))
        } else {
            emit(unknownJob(jobId, JobKind.ANALYSIS))
        }
    }

    override suspend fun getAnalysisResult(jobId: String) = analysisResults.value[jobId].asResult()

    override fun observeManualReviews(status: ReviewStatus?) =
        reviews.map { list -> list.filter { status == null || it.status == status } }

    override suspend fun resolveManualReview(reviewId: String, decision: ManualReviewDecision): ApiResult<Unit> {
        val review = reviews.value.find { it.id == reviewId } ?: return null.asResult()
        if (review.status != ReviewStatus.OPEN) return unsupported()
        val status = when (decision.action) {
            ReviewAction.APPROVE -> ReviewStatus.APPROVED
            ReviewAction.EDIT -> ReviewStatus.EDITED
            ReviewAction.IGNORE -> ReviewStatus.IGNORED
        }
        val patch = decision.patch
        if (decision.action == ReviewAction.EDIT && patch == null) return invalidArgument()
        val candidates = if (decision.action != ReviewAction.EDIT || patch == null) review.candidates
        else review.candidates.map { item ->
            item.copy(
                kind = patch.kind.or(item.kind),
                title = patch.title.or(item.title),
                description = patch.description.or(item.description),
                submissionFormat = patch.submissionFormat.or(item.submissionFormat),
                submissionMethod = patch.submissionMethod.or(item.submissionMethod),
                gradingCriteria = patch.gradingCriteria.or(item.gradingCriteria),
                availableAt = patch.availableAt.or(item.availableAt),
                dueAt = patch.dueAt.or(item.dueAt),
                evidence = patch.evidence.or(item.evidence),
            )
        }
        reviews.update { list -> list.map { if (it.id == reviewId) it.copy(status = status, candidates = candidates) else it } }
        return ApiResult.Success(Unit)
    }

    override suspend fun getGoogleCalendars() = ApiResult.Success(listOf(google))

    override fun observeGoogleEvents(calendarId: String, range: DateRange) = flowOf(
        listOf(fixtures.googleEvent).filter { calendarId == google.id && it.startAt.date in range.from..range.to },
    )

    override suspend fun exportToGoogle(localItemIds: List<String>, calendarId: String) = unsupported()

    override suspend fun setAutoExport(enabled: Boolean, calendarId: String?): ApiResult<Unit> {
        if (enabled && calendarId != google.id) return invalidArgument()
        val result = preferences.updateSettings(
            AppSettingsPatch(
                googleAutoExport = PatchField.Set(enabled),
                googleAutoExportCalendarId = PatchField.Set(calendarId),
            ),
        )
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    override suspend fun syncExportedLocalEvent(localItemId: String) = unsupported()
    override suspend fun deleteExportedLocalEvent(localItemId: String) = unsupported()

    private fun unknownJob(id: String, kind: JobKind) =
        JobState(id, kind, JobStatus.FAILED, null, null, null, ApiErrorCode.NOT_FOUND)

    private data class SyncPreview(val provider: Provider, val courseIds: List<String>?, val state: JobState)

    private data class DemoAISettings(
        val mode: AIMode = AIMode.DISABLED,
        val personalServer: AIConfigPublic? = null,
    ) {
        fun asPublicConfig(): AIConfigPublic {
            if (mode == AIMode.PERSONAL_SERVER && personalServer != null) return personalServer

            val readiness = when (mode) {
                AIMode.ON_DEVICE, AIMode.CHATGPT_SUBSCRIPTION -> AIReadiness.UNAVAILABLE
                AIMode.PERSONAL_SERVER -> AIReadiness.NEEDS_SETUP
                AIMode.DISABLED -> AIReadiness.DISABLED
            }
            return AIConfigPublic(null, null, false, false, mode, readiness)
        }
    }
}
