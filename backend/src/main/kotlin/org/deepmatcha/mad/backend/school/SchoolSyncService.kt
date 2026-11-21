package org.deepmatcha.mad.backend.school

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.JobKind
import org.deepmatcha.mad.contract.JobState
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.SourceRef
import org.deepmatcha.mad.contract.SyncService
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.contract.SyncStatus

/** One durable attempt. Failed courses stay in [remainingCourseIds] for retry. */
internal data class StoredSyncJob(
    val id: String,
    val status: JobStatus,
    val total: Int,
    val remainingCourseIds: List<String>,
    val failures: Map<String, ApiErrorCode> = emptyMap(),
    val succeededCount: Int = 0,
    val runningCourseId: String? = null,
    val previousCourseStatus: SyncStatus? = null,
    val error: ApiErrorCode? = null,
    val requeuedCourseIds: Set<String> = emptySet(),
) {
    val active: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
    val processed: Int get() = total - remainingCourseIds.size + failures.size
    val retryCourseIds: List<String> get() = if (active) emptyList() else remainingCourseIds - requeuedCourseIds
    val hasRetryWork: Boolean get() = retryCourseIds.isNotEmpty()

    fun asJobState() = JobState(
        jobId = id,
        kind = JobKind.SYNC,
        state = status,
        progress = if (total == 0) 1f else processed.toFloat() / total,
        processed = processed,
        total = total,
        errorCode = error,
    )
}

internal class SchoolSyncService(
    private val store: SchoolStore,
    private val source: SchoolAssignmentSource,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : SyncService {
    private val assignmentStore: SchoolAssignmentSyncStore = store
    private val lifecycle = Mutex()
    private var worker: Pair<String, Job>? = null

    override suspend fun startSync(provider: Provider, courseIds: List<String>?): ApiResult<String> {
        if (provider != Provider.SCHOOL) return ApiResult.Failure(ApiErrorCode.UNSUPPORTED, false)
        return enqueue(courseIds, retryOf = null)
    }

    override fun observeSyncJob(jobId: String) = store.state.map { state ->
        state.syncJobs[jobId]?.asJobState()
            ?: JobState(jobId, JobKind.SYNC, JobStatus.FAILED, null, null, null, ApiErrorCode.NOT_FOUND)
    }.distinctUntilChanged()

    override fun observeSyncStatus(provider: Provider) = if (provider == Provider.SCHOOL) {
        store.state.map { it.syncStatus }.distinctUntilChanged()
    } else {
        flowOf(SyncStatus(SyncState.UNSUPPORTED, null, null, ApiErrorCode.UNSUPPORTED))
    }

    override fun observeCourseSyncStatus(courseId: String) = store.state.map { state ->
        state.courses[courseId]?.course?.sync ?: SyncStatus(SyncState.IDLE, null, null, null)
    }.distinctUntilChanged()

    override suspend fun retrySync(jobId: String): ApiResult<String> = enqueue(null, retryOf = jobId)

    override suspend fun cancelSync(jobId: String): ApiResult<Unit> = lifecycle.withLock {
        withContext(NonCancellable) {
            val result = store.update { state ->
                val job = state.syncJobs[jobId]
                when {
                    job == null -> state to ApiResult.Failure(ApiErrorCode.NOT_FOUND, false)
                    job.active -> state.cancelJob(job) to ApiResult.Success(Unit)
                    job.status == JobStatus.CANCELLED -> state to ApiResult.Success(Unit)
                    else -> state to ApiResult.Failure(ApiErrorCode.CONFLICT, false)
                }
            }
            if (result is ApiResult.Success) worker?.takeIf { it.first == jobId }?.second?.cancel()
            result
        }
    }

    private suspend fun enqueue(courseIds: List<String>?, retryOf: String?): ApiResult<String> =
        lifecycle.withLock {
            // Keep the queue write and worker launch together if the requesting coroutine stops.
            withContext(NonCancellable) {
                if (!scope.isActive) return@withContext ApiResult.Failure(ApiErrorCode.CANCELLED, true)
                when (val result = store.update { it.enqueueJob(courseIds, retryOf, clock.instant()) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> {
                        val job = result.data
                        if (job.active) {
                            worker = job.id to scope.launch(start = CoroutineStart.UNDISPATCHED) { runJob(job) }
                        }
                        ApiResult.Success(job.id)
                    }
                }
            }
        }

    private suspend fun runJob(job: StoredSyncJob) {
        try {
            val result = syncCourses(job)
            if (result is ApiResult.Failure) failJob(job.id, result.code)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                store.update { state ->
                    val running = state.syncJobs[job.id]?.takeIf { it.active }
                    (if (running == null) state else state.cancelJob(running)) to ApiResult.Success(Unit)
                }
            }
            throw cancelled
        } catch (_: Exception) {
            failJob(job.id, ApiErrorCode.INTERNAL)
        }
    }

    private suspend fun syncCourses(job: StoredSyncJob): ApiResult<Unit> {
        for (courseId in job.remainingCourseIds) {
            currentCoroutineContext().ensureActive()
            val target = when (val result = store.update { it.beginCourse(job.id, courseId, clock.instant()) }) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> result.data ?: continue
            }
            val checkpoint = assignmentStore.readCheckpoint(courseId)
            val result = fetch(target.externalId, checkpoint)
            currentCoroutineContext().ensureActive()
            val applied = assignmentStore.recordAttempt(job.id, courseId, checkpoint, result, clock.instant())
            if (applied is ApiResult.Failure) return applied
        }
        return ApiResult.Success(Unit)
    }

    private suspend fun fetch(externalId: String?, checkpoint: AssignmentCheckpoint?): ApiResult<AssignmentDelta> {
        if (externalId == null) return ApiResult.Failure(ApiErrorCode.SOURCE_UNAVAILABLE, false)
        return try {
            source.fetchChanges(externalId, checkpoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ApiResult.Failure(ApiErrorCode.NETWORK, true)
        } catch (_: Exception) {
            ApiResult.Failure(ApiErrorCode.INTERNAL, true)
        }
    }

    private suspend fun failJob(jobId: String, error: ApiErrorCode) {
        store.update { state ->
            val job = state.syncJobs[jobId]?.takeIf { it.active }
            val failed = if (job == null) state else state.failRunningCourse(job, error).finishJob(job, error)
            failed to ApiResult.Success(Unit)
        }
    }
}

private fun SchoolState.enqueueJob(
    requested: List<String>?,
    retryOf: String?,
    now: Instant,
): Pair<SchoolState, ApiResult<StoredSyncJob>> {
    if (syncJobs.values.any { it.active }) return this to ApiResult.Failure(ApiErrorCode.CONFLICT, true)
    val previous = if (retryOf == null) {
        null
    } else {
        syncJobs[retryOf] ?: return this to ApiResult.Failure(ApiErrorCode.NOT_FOUND, false)
    }
    if (previous != null && !previous.hasRetryWork) return this to ApiResult.Failure(ApiErrorCode.CONFLICT, false)
    if (requested?.any { it !in courses } == true) return this to ApiResult.Failure(ApiErrorCode.NOT_FOUND, false)
    val targets = (previous?.retryCourseIds ?: requested?.distinct() ?: courses.keys)
        .filter { courses[it]?.course?.let(::isCourseSyncEnabled) == true }
    val job = StoredSyncJob(
        id = UUID.randomUUID().toString(),
        status = if (targets.isEmpty()) JobStatus.SUCCEEDED else JobStatus.QUEUED,
        total = targets.size,
        remainingCourseIds = targets,
    )
    val jobs = if (previous == null || targets.isEmpty()) syncJobs else {
        syncJobs + (previous.id to previous.copy(requeuedCourseIds = previous.requeuedCourseIds + targets))
    }
    val status = if (targets.isEmpty()) syncStatus
    else syncStatus.copy(state = SyncState.RUNNING, lastAttemptAt = now, errorCode = null)
    return copy(syncJobs = (jobs + (job.id to job)).retainOperationalJobs(), syncStatus = status) to ApiResult.Success(job)
}

private fun SchoolState.beginCourse(
    jobId: String,
    courseId: String,
    now: Instant,
): Pair<SchoolState, ApiResult<SourceRef?>> {
    val job = syncJobs[jobId]?.takeIf { it.active } ?: return this to ApiResult.Success(null)
    val course = courses[courseId]
    if (course == null || !isCourseSyncEnabled(course.course)) {
        return recordOutcome(job.copy(remainingCourseIds = job.remainingCourseIds - courseId)) to ApiResult.Success(null)
    }
    val previous = course.course.sync
    val running = previous.copy(state = SyncState.RUNNING, lastAttemptAt = now, errorCode = null)
    val state = copy(courses = courses + (courseId to course.copy(course = course.course.copy(sync = running))))
        .withJob(job.copy(status = JobStatus.RUNNING, runningCourseId = courseId, previousCourseStatus = previous))
    return state to ApiResult.Success(course.course.source)
}

internal fun SchoolState.recordCourseResult(
    jobId: String,
    courseId: String,
    checkpoint: AssignmentCheckpoint?,
    fetched: ApiResult<AssignmentDelta>,
    now: Instant,
): SchoolState {
    // A cancelled job cannot publish late results, even if the source kept fetching.
    val job = syncJobs[jobId]?.takeIf { it.active && it.runningCourseId == courseId } ?: return this
    val course = courses[courseId]?.course
    if (course == null || !isCourseSyncEnabled(course)) {
        return restoreCourseStatus(job).recordOutcome(job.copy(remainingCourseIds = job.remainingCourseIds - courseId))
    }
    val (current, result) = when (fetched) {
        is ApiResult.Success -> applyAssignmentDelta(courseId, checkpoint, fetched.data, now)
        is ApiResult.Failure -> this to fetched
    }
    return when (result) {
        is ApiResult.Success -> current.copy(syncStatus = syncStatus.copy(lastSuccessAt = now)).recordOutcome(
            job.copy(remainingCourseIds = job.remainingCourseIds - courseId, succeededCount = job.succeededCount + 1),
        )
        is ApiResult.Failure -> current.failRunningCourse(job, result.code).recordOutcome(
            job.copy(failures = job.failures + (courseId to result.code)),
        )
    }
}

private fun SchoolState.recordOutcome(job: StoredSyncJob): SchoolState {
    val recorded = job.copy(runningCourseId = null, previousCourseStatus = null)
    return if (recorded.processed == recorded.total) finishJob(recorded, recorded.failures.values.firstOrNull())
    else withJob(recorded)
}

private fun SchoolState.withJob(job: StoredSyncJob): SchoolState {
    val jobs = syncJobs + (job.id to job)
    return copy(syncJobs = if (job.active) jobs else jobs.retainOperationalJobs())
}

private fun SchoolState.finishJob(job: StoredSyncJob, error: ApiErrorCode?): SchoolState {
    val status = when {
        error == null -> JobStatus.SUCCEEDED
        job.succeededCount > 0 -> JobStatus.PARTIAL
        else -> JobStatus.FAILED
    }
    val state = when (status) {
        JobStatus.SUCCEEDED -> SyncState.SUCCESS
        JobStatus.PARTIAL -> SyncState.PARTIAL_SUCCESS
        else -> error.toSyncFailureState()
    }
    return withJob(job.copy(status = status, error = error, runningCourseId = null, previousCourseStatus = null))
        .copy(syncStatus = syncStatus.copy(state = state, errorCode = error))
}

private fun SchoolState.failRunningCourse(job: StoredSyncJob, error: ApiErrorCode): SchoolState {
    val courseId = job.runningCourseId ?: return this
    val course = courses[courseId]?.course ?: return this
    if (!isCourseSyncEnabled(course)) return restoreCourseStatus(job)
    val attemptedAt = course.sync.lastAttemptAt ?: return this
    return withCourseFailure(courseId, error, attemptedAt)
}

private fun SchoolState.restoreCourseStatus(job: StoredSyncJob): SchoolState {
    val courseId = job.runningCourseId ?: return this
    val course = courses[courseId] ?: return this
    val status = job.previousCourseStatus ?: return this
    return copy(courses = courses + (courseId to course.copy(course = course.course.copy(sync = status))))
}

private fun SchoolState.cancelJob(job: StoredSyncJob): SchoolState {
    val error = ApiErrorCode.CANCELLED
    val cancelled = job.copy(
        status = JobStatus.CANCELLED,
        error = error,
        runningCourseId = null,
        previousCourseStatus = null,
    )
    val state = if (job.succeededCount > 0) SyncState.PARTIAL_SUCCESS else SyncState.FAILED
    return failRunningCourse(job, error).withJob(cancelled)
        .copy(syncStatus = syncStatus.copy(state = state, errorCode = error))
}

internal fun SchoolState.recoverInterruptedSync(): SchoolState =
    syncJobs.values.filter { it.active }.fold(this) { state, job -> state.cancelJob(job) }

internal fun ApiErrorCode?.toSyncFailureState(): SyncState = when (this) {
    ApiErrorCode.AUTH_EXPIRED, ApiErrorCode.UNAUTHENTICATED -> SyncState.AUTH_REQUIRED
    ApiErrorCode.UNSUPPORTED -> SyncState.UNSUPPORTED
    else -> SyncState.FAILED
}

private const val RECENT_FINISHED_JOB_LIMIT = 32

// Keep recoverable jobs until their remaining work has been requeued.
private fun Map<String, StoredSyncJob>.retainOperationalJobs(): Map<String, StoredSyncJob> {
    val (recoverable, finished) = values.partition { it.active || it.hasRetryWork }
    val retainedIds = (recoverable + finished.takeLast(RECENT_FINISHED_JOB_LIMIT)).mapTo(mutableSetOf()) { it.id }
    return filterKeys { it in retainedIds }
}
