package org.deepmatcha.mad.contract

import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import java.time.Instant

enum class AuthState { DISCONNECTED, AUTHENTICATED, EXPIRED }
enum class SyncState { IDLE, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, AUTH_REQUIRED, UNSUPPORTED }
enum class JobKind { SYNC, ANALYSIS }
enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED }

data class SyncStatus(
    val state: SyncState,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val errorCode: ApiErrorCode?,
)

data class IntegrationStatus(
    val provider: Provider,
    val connected: Boolean,
    val authState: AuthState,
    val sync: SyncStatus?,
)

data class JobState(
    val jobId: String,
    val kind: JobKind,
    val state: JobStatus,
    val progress: Float?,
    val processed: Int?,
    val total: Int?,
    val errorCode: ApiErrorCode?,
)

data class ConnectionTestResult(
    val connected: Boolean,
    val capabilities: Set<String>,
    val errorCode: ApiErrorCode?,
)

/** The service supplies one of the Android launch mechanisms for a successful request. */
data class AuthLaunchRequest(
    val requestId: String,
    val launchIntent: Intent?,
    val intentSender: IntentSenderRequest?,
)

data class GoogleAuthResult(
    val requestId: String,
    val resultCode: Int,
    val data: Intent?,
)

data class GoogleCalendarInfo(
    val id: String,
    val name: String,
    val primary: Boolean,
    val writable: Boolean,
)

enum class ExportState { SENT, QUEUED, FAILED, NOT_APPLICABLE }

data class ExportReceipt(
    val localItemId: String,
    val googleEventId: String?,
    val state: ExportState,
    val errorCode: ApiErrorCode?,
)

data class DeleteReceipt(
    val localDeleted: Boolean,
    val remoteState: ExportState,
)
