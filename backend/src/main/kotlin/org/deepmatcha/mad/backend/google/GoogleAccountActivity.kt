package org.deepmatcha.mad.backend.google

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.deepmatcha.mad.contract.ApiErrorCode

private const val GOOGLE = "com.google"
private val scopes = listOf("calendar.events", "calendar.calendarlist.readonly").map {
    Scope("https://www.googleapis.com/auth/$it")
}
internal const val ACCOUNT_RESULT = "mad.selectedAccount"
internal const val AUTH_ERROR = "mad.authorizationError"

internal class AndroidGoogleAccounts(context: Context) {
    val client = Identity.getAuthorizationClient(context)
    suspend fun authorize(name: String): AuthorizationResult = client.authorize(
        AuthorizationRequest.builder().setAccount(Account(name, GOOGLE)).setRequestedScopes(scopes).build()
    ).resultOrFailure()

    suspend fun access(name: String): String = authorize(name).accessToken ?: throw GoogleFailure(ApiErrorCode.AUTH_EXPIRED)
    suspend fun invalidate(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).resultOrFailure()
    }
    suspend fun revoke(name: String) {
        client.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(name, GOOGLE)).setScopes(scopes).build()).resultOrFailure()
    }
}

private fun Exception.failure() = when ((this as? ApiException)?.statusCode) {
    CommonStatusCodes.NETWORK_ERROR -> GoogleFailure(ApiErrorCode.NETWORK, true)
    CommonStatusCodes.CANCELED -> GoogleFailure(ApiErrorCode.CANCELLED)
    CommonStatusCodes.DEVELOPER_ERROR -> GoogleFailure(ApiErrorCode.INVALID_ARGUMENT)
    else -> GoogleFailure(ApiErrorCode.UNAUTHENTICATED)
}

private suspend fun <T> Task<T>.resultOrFailure(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it.failure()) }
    addOnCanceledListener { continuation.resumeWithException(GoogleFailure(ApiErrorCode.CANCELLED)) }
}

/** Only the chosen account identifier crosses the Activity result boundary. */
class GoogleAccountActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var name: String? = null
    private var waitingForConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = savedInstanceState?.getString(ACCOUNT_RESULT)
        waitingForConsent = savedInstanceState?.getBoolean("consent") ?: false
        if (savedInstanceState == null) {
            startActivityForResult(AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE), null, null, null, null), PICK)
        } else if (name != null && !waitingForConsent) authorize(checkNotNull(name))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) { finish(); return }
        when (requestCode) {
            PICK -> {
                val chosen = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (chosen.isNullOrBlank() || data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != GOOGLE) { finish(); return }
                name = chosen
                authorize(chosen)
            }
            CONSENT -> try {
                val result = AndroidGoogleAccounts(this).client.getAuthorizationResultFromIntent(data)
                if (result.accessToken != null) success() else reject(ApiErrorCode.UNAUTHENTICATED)
            } catch (e: ApiException) { reject(e.failure().code) }
        }
    }

    private fun authorize(chosen: String) = scope.launch {
        try {
            val result = AndroidGoogleAccounts(this@GoogleAccountActivity).authorize(chosen)
            val pending = result.pendingIntent
            when {
                result.accessToken != null -> success()
                pending != null -> {
                    waitingForConsent = true
                    startIntentSenderForResult(pending.intentSender, CONSENT, null, 0, 0, 0)
                }
                else -> reject(ApiErrorCode.UNAUTHENTICATED)
            }
        } catch (e: GoogleFailure) { reject(e.code) }
        catch (e: IntentSender.SendIntentException) { reject(ApiErrorCode.SOURCE_UNAVAILABLE) }
    }
    private fun success() {
        setResult(RESULT_OK, Intent().putExtra(ACCOUNT_RESULT, name))
        finish()
    }
    private fun reject(code: ApiErrorCode) {
        setResult(RESULT_CANCELED, Intent().putExtra(AUTH_ERROR, code.name))
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ACCOUNT_RESULT, name)
        outState.putBoolean("consent", waitingForConsent)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val PICK = 1
        const val CONSENT = 2
    }
}
