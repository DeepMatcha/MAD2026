package org.deepmatcha.mad.backend

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.SyncService

private const val SCHOOL_SYNC_JOB_ID = 0x4D414401

internal fun scheduleSchoolSync(context: Context, intervalMinutes: Int) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val interval = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
    val service = ComponentName(context, SchoolSyncJobService::class.java)
    val current = scheduler.getPendingJob(SCHOOL_SYNC_JOB_ID)
    if (current?.service == service && current.isPeriodic && current.intervalMillis == interval) return

    val job = JobInfo.Builder(SCHOOL_SYNC_JOB_ID, service)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true)
        .setPeriodic(interval, JobInfo.getMinFlexMillis())
        .build()
    check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule school sync" }
}

/** Android dispatches the periodic run in the app process, including after a process restart. */
internal class SchoolSyncJobService : JobService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentRun: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        currentRun = scope.launch {
            val run = coroutineContext.job
            try {
                val backend = SyntheticSchoolRuntime.get(applicationContext)
                runScheduledSync(backend.sync)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Keep the periodic registration after a local I/O failure.
            } catch (_: IllegalStateException) {
                // An unsuccessful opening can be retried at the next scheduled run.
            } finally {
                if (currentRun === run) {
                    currentRun = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        currentRun?.cancel()
        currentRun = null
        return false
    }

    override fun onDestroy() {
        currentRun = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runScheduledSync(sync: SyncService) {
        var jobId: String? = null
        try {
            withContext(NonCancellable) {
                val started = sync.startSync()
                if (started is ApiResult.Success) jobId = started.data
            }
            val startedJobId = jobId ?: return
            sync.observeSyncJob(startedJobId).first {
                it.state != JobStatus.QUEUED && it.state != JobStatus.RUNNING
            }
        } catch (cancelled: CancellationException) {
            jobId?.let { withContext(NonCancellable) { sync.cancelSync(it) } }
            throw cancelled
        }
    }
}
