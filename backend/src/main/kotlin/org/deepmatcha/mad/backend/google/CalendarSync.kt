package org.deepmatcha.mad.backend.google

import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deepmatcha.mad.backend.school.*
import org.deepmatcha.mad.contract.*

/** Routes the existing SyncService contract to the selected provider. Google reads never run on subscription. */
internal class CalendarSync(private val school: SyncService, private val google: GoogleCalendarService,
    private val scope: CoroutineScope, private val clock: Clock) : SyncService by school {
    private val lifecycle = Mutex()
    private val jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
    private val status = MutableStateFlow(SyncStatus(SyncState.IDLE, null, null, null))
    private var worker: Job? = null

    override suspend fun startSync(provider: Provider, courseIds: List<String>?): ApiResult<String> {
        if (provider != Provider.GOOGLE_CALENDAR) return school.startSync(provider, courseIds)
        if (courseIds != null) return invalid()
        return lifecycle.withLock {
            if (worker?.isCompleted == false) return@withLock ApiResult.Failure(ApiErrorCode.CONFLICT, true)
            val id = "google:" + newId()
            val previousSuccess = status.value.lastSuccessAt
            val now = clock.instant()
            val running = JobState(id, JobKind.SYNC, JobStatus.RUNNING, 0f, 0, 1, null)
            jobs.update { it + (id to running) }
            status.value = SyncStatus(SyncState.RUNNING, now, previousSuccess, null)
            worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val today = LocalDate.now(clock.withZone(seoul))
                    val result = google.refresh(DateRange(today.minusYears(1), today.plusYears(1)))
                    val error = (result as? ApiResult.Failure)?.code
                    jobs.update { it + (id to running.copy(state = if (error == null) JobStatus.SUCCEEDED else JobStatus.FAILED,
                        progress = 1f, processed = 1, errorCode = error)) }
                    status.value = SyncStatus(when (error) {
                        null -> SyncState.SUCCESS
                        ApiErrorCode.AUTH_EXPIRED, ApiErrorCode.UNAUTHENTICATED -> SyncState.AUTH_REQUIRED
                        else -> SyncState.FAILED
                    }, now, if (error == null) clock.instant() else previousSuccess, error)
                } catch (e: CancellationException) {
                    jobs.update { it + (id to running.copy(state = JobStatus.CANCELLED, errorCode = ApiErrorCode.CANCELLED)) }
                    status.value = SyncStatus(SyncState.IDLE, now, previousSuccess, ApiErrorCode.CANCELLED)
                    throw e
                }
            }
            ApiResult.Success(id)
        }
    }

    override fun observeSyncJob(jobId: String) = if (!jobId.startsWith("google:")) school.observeSyncJob(jobId)
        else jobs.map { it[jobId] ?: JobState(jobId, JobKind.SYNC, JobStatus.FAILED, null, null, null, ApiErrorCode.NOT_FOUND) }.distinctUntilChanged()

    override fun observeSyncStatus(provider: Provider) = if (provider == Provider.GOOGLE_CALENDAR) status.asStateFlow() else school.observeSyncStatus(provider)
    override suspend fun cancelSync(jobId: String): ApiResult<Unit> {
        if (!jobId.startsWith("google:")) return school.cancelSync(jobId)
        return lifecycle.withLock {
            val job = jobs.value[jobId] ?: return@withLock notFound()
            if (job.state != JobStatus.RUNNING) return@withLock ApiResult.Failure(ApiErrorCode.CONFLICT, false)
            worker?.cancelAndJoin()
            ApiResult.Success(Unit)
        }
    }
    override suspend fun retrySync(jobId: String): ApiResult<String> {
        if (!jobId.startsWith("google:")) return school.retrySync(jobId)
        val job = jobs.value[jobId] ?: return notFound()
        if (job.state !in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) return ApiResult.Failure(ApiErrorCode.CONFLICT, false)
        return startSync(Provider.GOOGLE_CALENDAR)
    }
}
