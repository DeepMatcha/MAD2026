package org.deepmatcha.mad.backend.ai

import org.deepmatcha.mad.contract.AIMode
import org.deepmatcha.mad.contract.AnalysisResult
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.JobState
import org.deepmatcha.mad.contract.JobStatus
import org.deepmatcha.mad.contract.ReviewItem

internal data class StoredAnalysis(
    val mode: AIMode = AIMode.DISABLED,
    val server: StoredPersonalServer? = null,
    val jobs: Map<String, StoredAnalysisJob> = emptyMap(),
    val reviews: Map<String, ReviewItem> = emptyMap(),
) {
    fun cancelActiveJobs() = copy(jobs = jobs.mapValues { (_, job) ->
        if (job.active) job.cancelled() else job
    })
}

internal data class StoredPersonalServer(
    val baseUrl: String,
    val modelId: String?,
    val encryptedKey: String?,
)

internal data class StoredAnalysisJob(
    val state: JobState,
    val result: AnalysisResult? = null,
) {
    val active: Boolean
        get() = state.state == JobStatus.QUEUED || state.state == JobStatus.RUNNING

    fun cancelled() = copy(state = state.copy(state = JobStatus.CANCELLED, errorCode = ApiErrorCode.CANCELLED))
}
