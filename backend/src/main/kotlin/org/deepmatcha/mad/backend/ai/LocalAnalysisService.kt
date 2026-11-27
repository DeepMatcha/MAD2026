package org.deepmatcha.mad.backend.ai

import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.notFound
import org.deepmatcha.mad.backend.school.or
import org.deepmatcha.mad.backend.school.orNotFound
import org.deepmatcha.mad.contract.AIConfigPublic
import org.deepmatcha.mad.contract.AIMode
import org.deepmatcha.mad.contract.AIReadiness
import org.deepmatcha.mad.contract.AnalysisResult
import org.deepmatcha.mad.contract.AnalysisService
import org.deepmatcha.mad.contract.AnalysisState
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.ConnectionTestResult
import org.deepmatcha.mad.contract.ExtractedItem
import org.deepmatcha.mad.contract.ExtractedItemPatch
import org.deepmatcha.mad.contract.JobKind
import org.deepmatcha.mad.contract.JobState
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.ManualReviewDecision
import org.deepmatcha.mad.contract.ReviewAction
import org.deepmatcha.mad.contract.ReviewItem
import org.deepmatcha.mad.contract.ReviewReason
import org.deepmatcha.mad.contract.ReviewStatus
import org.deepmatcha.mad.contract.SchoolRepository

internal class LocalAnalysisService(
    private val store: SchoolStore,
    private val school: SchoolRepository,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val device: OnDeviceTextEngine? = null,
    private val personal: PersonalServerClient = PersonalServerClient(),
    private val secrets: AnalysisSecrets = AndroidAnalysisSecrets,
) : AnalysisService {
    private val commands = Mutex()
    private var worker: Job? = null

    @Volatile
    private var connectedServer: StoredPersonalServer? = null

    @Volatile
    private var deviceChecked = false

    override suspend fun getAIConfig(): ApiResult<AIConfigPublic> {
        val profile = store.state.value.analysis
        val server = profile.server.takeIf { profile.mode == AIMode.PERSONAL_SERVER }
        val onDevice = device.takeIf { profile.mode == AIMode.ON_DEVICE }
        val readiness = when (profile.mode) {
            AIMode.DISABLED -> AIReadiness.DISABLED
            AIMode.CHATGPT_SUBSCRIPTION -> AIReadiness.UNAVAILABLE
            AIMode.ON_DEVICE -> when {
                onDevice == null -> AIReadiness.UNAVAILABLE
                deviceChecked -> AIReadiness.CONNECTED
                else -> AIReadiness.CONFIGURED
            }
            AIMode.PERSONAL_SERVER -> when {
                server == null -> AIReadiness.NEEDS_SETUP
                server == connectedServer -> AIReadiness.CONNECTED
                else -> AIReadiness.CONFIGURED
            }
        }
        return ApiResult.Success(
            AIConfigPublic(
                baseUrl = server?.baseUrl,
                modelId = server?.modelId ?: onDevice?.modelId,
                configured = readiness == AIReadiness.CONFIGURED || readiness == AIReadiness.CONNECTED,
                connected = readiness == AIReadiness.CONNECTED,
                mode = profile.mode,
                readiness = readiness,
            ),
        )
    }

    override suspend fun setAIMode(mode: AIMode) = changeConfiguration { it.copy(mode = mode) }

    override suspend fun setAIConfig(baseUrl: String, apiKey: String, modelId: String?): ApiResult<Unit> {
        val validated = when (val result = personal.validate(baseUrl, apiKey, modelId)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        val encrypted = withContext(Dispatchers.IO) {
            try {
                validated.key.takeIf { it.isNotEmpty() }?.let(secrets::seal)
            } catch (_: Exception) {
                return@withContext null
            }
        }
        if (validated.key.isNotEmpty() && encrypted == null) return failure(ApiErrorCode.INTERNAL)
        val server = StoredPersonalServer(validated.baseUrl, validated.modelId, encrypted)
        return changeConfiguration { it.copy(mode = AIMode.PERSONAL_SERVER, server = server) }
    }

    override suspend fun testAIConnection(): ApiResult<ConnectionTestResult> {
        val current = store.state.value.analysis
        val server = current.server
        return when (current.mode) {
            AIMode.PERSONAL_SERVER -> {
                val config = when (val resolved = loadPersonalServer(server)) {
                    is ApiResult.Failure -> {
                        if (connectedServer == server) connectedServer = null
                        return disconnected(resolved.code)
                    }
                    is ApiResult.Success -> resolved.data
                }
                when (val result = personal.test(config)) {
                    is ApiResult.Failure -> {
                        if (connectedServer == server) connectedServer = null
                        result
                    }
                    is ApiResult.Success -> {
                        if (store.state.value.analysis.server == server) {
                            connectedServer = server.takeIf { result.data.connected }
                        }
                        result
                    }
                }
            }
            AIMode.ON_DEVICE -> {
                val engine = device ?: return disconnected(ApiErrorCode.UNSUPPORTED)
                val result = safelyGenerate { engine.generate("Reply with the single word OK.") }
                deviceChecked = result is ApiResult.Success && result.data.isNotBlank()
                if (deviceChecked) ApiResult.Success(ConnectionTestResult(true, setOf("TEXT_ANALYSIS"), null))
                else disconnected((result as? ApiResult.Failure)?.code ?: ApiErrorCode.PARSE_FAILED)
            }
            AIMode.DISABLED, AIMode.CHATGPT_SUBSCRIPTION -> disconnected(ApiErrorCode.UNSUPPORTED)
        }
    }

    override suspend fun analyzeResource(resourceId: String): ApiResult<String> = commands.withLock {
        val current = store.state.value.analysis
        if (!scope.isActive) return@withLock failure(ApiErrorCode.CANCELLED)
        if (current.jobs.values.any { it.active }) return@withLock failure(ApiErrorCode.CONFLICT, retryable = true)
        val generate: suspend (String) -> ApiResult<String> = when (current.mode) {
            AIMode.ON_DEVICE -> device?.let { it::generate } ?: return@withLock failure(ApiErrorCode.UNSUPPORTED)
            AIMode.PERSONAL_SERVER -> {
                val config = when (val resolved = loadPersonalServer(current.server)) {
                    is ApiResult.Failure -> return@withLock resolved
                    is ApiResult.Success -> resolved.data
                }
                suspendGenerator(config)
            }
            AIMode.DISABLED, AIMode.CHATGPT_SUBSCRIPTION -> return@withLock failure(ApiErrorCode.UNSUPPORTED)
        }
        val resource = when (val result = school.getResource(resourceId)) {
            is ApiResult.Failure -> return@withLock result
            is ApiResult.Success -> result.data
        }
        val input = withContext(Dispatchers.Default) { ResourceExtraction.from(resource) }
            ?: return@withLock failure(ApiErrorCode.SOURCE_UNAVAILABLE)
        val id = UUID.randomUUID().toString()
        val queued = StoredAnalysisJob(JobState(id, JobKind.ANALYSIS, JobStatus.QUEUED, null, 0, 1, null))
        // Publish and launch together even if the requesting screen disappears meanwhile.
        withContext(NonCancellable) {
            val result = store.update { state ->
                state.copy(analysis = state.analysis.copy(jobs = state.analysis.jobs + (id to queued))) to ApiResult.Success(id)
            }
            if (result is ApiResult.Success) {
                worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runAnalysis(id, resourceId, input, generate)
                }
            }
            result
        }
    }

    override fun observeAnalysisJob(jobId: String) = store.state.map {
        it.analysis.jobs[jobId]?.state
            ?: JobState(jobId, JobKind.ANALYSIS, JobStatus.FAILED, null, null, null, ApiErrorCode.NOT_FOUND)
    }.distinctUntilChanged()

    override suspend fun getAnalysisResult(jobId: String) =
        store.state.value.analysis.jobs[jobId]?.result.orNotFound()

    override fun observeManualReviews(status: ReviewStatus?) =
        store.state.map { state -> state.analysis.reviews.values.filter { status == null || it.status == status } }
            .distinctUntilChanged()

    override suspend fun resolveManualReview(reviewId: String, decision: ManualReviewDecision): ApiResult<Unit> =
        store.update { state ->
            val review = state.analysis.reviews[reviewId] ?: return@update state to notFound()
            if (review.status != ReviewStatus.OPEN) return@update state to failure(ApiErrorCode.CONFLICT)
            if (decision.action != ReviewAction.IGNORE && review.candidates.size != 1) {
                return@update state to failure(ApiErrorCode.INVALID_ARGUMENT)
            }
            val updated = when (decision.action) {
                ReviewAction.APPROVE -> review.copy(status = ReviewStatus.APPROVED)
                ReviewAction.IGNORE -> review.copy(status = ReviewStatus.IGNORED)
                ReviewAction.EDIT -> {
                    val patch = decision.patch ?: return@update state to failure(ApiErrorCode.INVALID_ARGUMENT)
                    review.copy(status = ReviewStatus.EDITED, candidates = listOf(review.candidates.single().withPatch(patch)))
                }
            }
            state.copy(analysis = state.analysis.copy(reviews = state.analysis.reviews + (reviewId to updated))) to
                ApiResult.Success(Unit)
        }

    private suspend fun changeConfiguration(transform: (StoredAnalysis) -> StoredAnalysis): ApiResult<Unit> =
        commands.withLock {
            withContext(NonCancellable) {
                var changed = false
                val result = store.update { state ->
                    val updated = transform(state.analysis)
                    changed = updated != state.analysis
                    val next = if (changed) updated.cancelActiveJobs() else updated
                    state.copy(analysis = next) to ApiResult.Success(Unit)
                }
                if (changed && result is ApiResult.Success) worker?.cancel()
                result
            }
        }

    private suspend fun loadPersonalServer(server: StoredPersonalServer?): ApiResult<PersonalServerConfig> =
        withContext(Dispatchers.IO) {
            if (server == null) return@withContext failure(ApiErrorCode.INVALID_ARGUMENT)
            val key = try {
                server.encryptedKey?.let(secrets::unseal).orEmpty()
            } catch (_: Exception) {
                return@withContext failure(ApiErrorCode.AUTH_EXPIRED)
            }
            personal.validate(server.baseUrl, key, server.modelId)
        }

    private fun suspendGenerator(config: PersonalServerConfig): suspend (String) -> ApiResult<String> =
        { prompt -> personal.generate(config, prompt) }

    private suspend fun runAnalysis(
        id: String,
        resourceId: String,
        input: ResourceExtraction,
        generate: suspend (String) -> ApiResult<String>,
    ) {
        try {
            val started = store.update { state ->
                val stored = state.analysis.jobs[id]?.takeIf { it.active }
                    ?: return@update state to failure(ApiErrorCode.CANCELLED)
                val running = stored.copy(state = stored.state.copy(state = JobStatus.RUNNING))
                state.copy(analysis = state.analysis.copy(jobs = state.analysis.jobs + (id to running))) to
                    ApiResult.Success(Unit)
            }
            val extracted = when (started) {
                is ApiResult.Failure -> started
                is ApiResult.Success -> when (val generated = safelyGenerate { generate(input.prompt()) }) {
                    is ApiResult.Success -> input.parse(generated.data)
                    is ApiResult.Failure -> generated
                }
            }
            val persisted = store.update { state ->
                state.copy(analysis = state.analysis.finish(id, resourceId, extracted, clock)) to ApiResult.Success(Unit)
            }
            if (persisted is ApiResult.Failure) failActiveJob(id, persisted.code)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { failActiveJob(id, ApiErrorCode.CANCELLED) }
            throw cancelled
        } catch (_: Exception) {
            failActiveJob(id, ApiErrorCode.INTERNAL)
        }
    }

    private suspend fun failActiveJob(id: String, code: ApiErrorCode) {
        store.update { state ->
            val stored = state.analysis.jobs[id]?.takeIf { it.active } ?: return@update state to ApiResult.Success(Unit)
            val failed = if (code == ApiErrorCode.CANCELLED) stored.cancelled() else {
                stored.copy(state = stored.state.copy(state = JobStatus.FAILED, errorCode = code))
            }
            state.copy(analysis = state.analysis.copy(jobs = state.analysis.jobs + (id to failed))) to ApiResult.Success(Unit)
        }
    }
}

private fun StoredAnalysis.finish(
    id: String,
    resourceId: String,
    extracted: ApiResult<ExtractedItem?>,
    clock: Clock,
): StoredAnalysis {
    val stored = jobs[id]?.takeIf { it.active } ?: return this
    if (extracted is ApiResult.Failure) {
        return copy(jobs = jobs + (id to stored.copy(
            state = stored.state.copy(state = JobStatus.FAILED, errorCode = extracted.code),
        )))
    }
    val candidate = (extracted as ApiResult.Success).data
    val candidates = listOfNotNull(candidate)
    val reason = if (candidate == null || candidate.title == null || candidate.evidence.isEmpty()) {
        ReviewReason.INCOMPLETE
    } else {
        ReviewReason.NEEDS_CONFIRMATION
    }
    val result = AnalysisResult(resourceId, AnalysisState.MANUAL_REVIEW, candidates, candidate?.evidence.orEmpty(), clock.instant())
    val finished = stored.copy(result = result, state = stored.state.copy(state = JobStatus.SUCCEEDED, progress = 1f, processed = 1))
    return copy(
        jobs = jobs + (id to finished),
        reviews = reviews + (id to ReviewItem(id, resourceId, reason, candidates, ReviewStatus.OPEN)),
    )
}

private fun ExtractedItem.withPatch(patch: ExtractedItemPatch) = copy(
    kind = patch.kind.or(kind),
    title = patch.title.or(title),
    description = patch.description.or(description),
    submissionFormat = patch.submissionFormat.or(submissionFormat),
    submissionMethod = patch.submissionMethod.or(submissionMethod),
    gradingCriteria = patch.gradingCriteria.or(gradingCriteria),
    availableAt = patch.availableAt.or(availableAt),
    dueAt = patch.dueAt.or(dueAt),
    evidence = patch.evidence.or(evidence),
)

private suspend fun safelyGenerate(block: suspend () -> ApiResult<String>): ApiResult<String> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    failure(ApiErrorCode.INTERNAL)
}

private fun failure(code: ApiErrorCode, retryable: Boolean = false) = ApiResult.Failure(code, retryable)

private fun disconnected(code: ApiErrorCode) = ApiResult.Success(ConnectionTestResult(false, emptySet(), code))
