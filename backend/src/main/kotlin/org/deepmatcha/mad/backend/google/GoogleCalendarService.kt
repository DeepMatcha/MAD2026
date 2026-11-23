package org.deepmatcha.mad.backend.google

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.gson.JsonParseException
import java.time.format.DateTimeParseException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deepmatcha.mad.backend.school.*
import org.deepmatcha.mad.contract.*

internal class GoogleCalendarService(
    private val store: SchoolStore,
    private val context: Context?,
    private val remoteFor: (String) -> GoogleRemote = { name ->
        val accounts = AndroidGoogleAccounts(context ?: throw GoogleFailure(ApiErrorCode.UNSUPPORTED))
        GoogleCalendarApi({ accounts.access(name) }, accounts::invalidate)
    },
) : GoogleCalendarRepository, IntegrationsService {
    private val lock = Mutex()

    fun processPending(scope: CoroutineScope) = scope.launch {
        store.state.map { it.google.account to it.google.exports.filterValues(GoogleExport::pending) }
            .distinctUntilChanged().collectLatest { (account, pending) ->
                if (account == null || pending.isEmpty()) return@collectLatest
                var wait = 5000L
                do {
                    val retry = lock.withLock {
                        if (store.state.value.google.account != account) return@withLock false
                        var failed = false
                        for (key in pending.keys) {
                            val result = call { send(key, account) }
                            if (result is ApiResult.Failure && result.retryable) failed = true
                        }
                        failed
                    }
                    if (!retry) break
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(300000)
                } while (true)
            }
    }

    override fun observeIntegrations() = store.state.map { listOf(status(it.google)) }.distinctUntilChanged()

    private fun status(state: GoogleState) = IntegrationStatus(Provider.GOOGLE_CALENDAR, state.account != null && !state.expired,
        if (state.account == null) AuthState.DISCONNECTED else if (state.expired) AuthState.EXPIRED else AuthState.AUTHENTICATED, null)

    override suspend fun connectSchool(studentId: String, password: String) = unsupported()
    override suspend fun refreshSchoolAuth() = unsupported()

    override suspend fun beginGoogleAuth(): ApiResult<AuthLaunchRequest> {
        val app = context ?: return unsupported()
        val id = newId()
        return store.update { state -> state.copy(google = state.google.copy(authRequest = id)) to
            ApiResult.Success(AuthLaunchRequest(id, Intent(app, GoogleAccountActivity::class.java), null)) }
    }

    override suspend fun completeGoogleAuth(result: GoogleAuthResult): ApiResult<IntegrationStatus> = lock.withLock {
        if (store.state.value.google.authRequest != result.requestId) return@withLock invalid()
        val account = result.data?.getStringExtra(ACCOUNT_RESULT)
        if (result.resultCode != Activity.RESULT_OK || account.isNullOrBlank()) {
            val code = ApiErrorCode.entries.find { it.name == result.data?.getStringExtra(AUTH_ERROR) } ?: ApiErrorCode.CANCELLED
            return@withLock store.update { state -> state.copy(google = state.google.copy(authRequest = null)) to ApiResult.Failure(code, false) }
        }
        val calendars = when (val response = call { remoteFor(account).calendars() }) {
            is ApiResult.Failure -> return@withLock response
            is ApiResult.Success -> response.data
        }
        store.update { state ->
            if (state.google.authRequest != result.requestId) return@update state to invalid()
            val same = state.google.account == account
            val connected = state.google.connect(account, calendars)
            // Reconcile edits and deletions made while this account was disconnected.
            val google = connected.copy(exports = connected.exports.mapValues { (_, entry) ->
                val local = state.planner.events[entry.item.id]
                when {
                    entry.item.origin != Origin.USER || entry.item.timetableEntryId != null -> entry
                    local == null -> entry.copy(deleting = true, pending = true)
                    local != entry.item -> entry.copy(item = local, deleting = false, pending = true)
                    else -> entry
                }
            })
            state.copy(google = google, settings = if (same) state.settings else state.settings.copy(
                googleAutoExport = false, googleAutoExportCalendarId = null)) to ApiResult.Success(status(google))
        }
    }

    override suspend fun disconnectGoogle(): ApiResult<Unit> = lock.withLock {
        val account = store.state.value.google.let { it.account ?: it.pendingRevocation }
        val cleared = store.update { state ->
            state.copy(google = state.google.disconnect().copy(pendingRevocation = account),
                settings = state.settings.copy(googleAutoExport = false, googleAutoExportCalendarId = null)) to ApiResult.Success(Unit)
        }
        if (cleared is ApiResult.Failure || account == null) return@withLock cleared
        if (context != null) {
            val revoked = call { AndroidGoogleAccounts(context).revoke(account) }
            if (revoked is ApiResult.Failure) return@withLock revoked
        }
        store.update { it.copy(google = it.google.copy(pendingRevocation = null)) to ApiResult.Success(Unit) }
    }

    override suspend fun testIntegration(provider: Provider): ApiResult<ConnectionTestResult> {
        if (provider != Provider.GOOGLE_CALENDAR) return unsupported()
        return when (val result = getGoogleCalendars()) {
            is ApiResult.Failure -> ApiResult.Success(ConnectionTestResult(false, emptySet(), result.code))
            is ApiResult.Success -> ApiResult.Success(ConnectionTestResult(true, setOf("calendar.read") +
                if (result.data.any { it.writable }) setOf("calendar.write") else emptySet(), null))
        }
    }

    override suspend fun getGoogleCalendars() = lock.withLock { connected { _, remote ->
        val list = remote.calendars()
        requireStored(store.update { state -> state.copy(google = state.google.copy(calendars = list, expired = false)) to ApiResult.Success(Unit) })
        list
    } }

    override fun observeGoogleEvents(calendarId: String, range: DateRange) = store.state.map { state ->
        state.google.cache[calendarId].orEmpty().filter { it.overlaps(range) }
    }.distinctUntilChanged()

    suspend fun refresh(range: DateRange): ApiResult<Unit> = lock.withLock { connected { account, remote ->
        val calendars = remote.calendars()
        var exportFailure: ApiResult.Failure? = null
        for (key in store.state.value.google.exports.filterValues { it.pending }.keys) {
            val result = call { send(key, account) }
            if (result is ApiResult.Failure && exportFailure == null) exportFailure = result
        }
        val updates = calendars.associate { it.id to remote.events(it.id, range) }
        requireStored(store.update { state ->
            val cache = updates.mapValues { (id, rows) ->
                (rows + state.google.cache[id].orEmpty().filterNot { it.overlaps(range) }).distinctBy { it.id }
            }
            state.copy(google = state.google.copy(cache = cache, calendars = calendars, expired = false)) to ApiResult.Success(Unit)
        })
        exportFailure?.let { throw GoogleFailure(it.code, it.retryable) }
        Unit
    } }

    override suspend fun exportToGoogle(localItemIds: List<String>, calendarId: String): ApiResult<ExportReceipt> = lock.withLock {
        if (localItemIds.isEmpty()) return@withLock invalid()
        val account = store.state.value.google.account ?: return@withLock unauthenticated()
        val keys = localItemIds.distinct().map { exportKey(it, calendarId) }
        val result = store.update { state ->
            if (state.google.calendars.none { it.id == calendarId && it.writable }) return@update state to ApiResult.Failure(ApiErrorCode.FORBIDDEN, false)
            var google = state.google
            for (id in localItemIds.distinct()) {
                val item = state.item(id) ?: return@update state to notFound()
                if (item.origin == Origin.GOOGLE) return@update state to unsupported()
                google = google.queue(item, calendarId)
            }
            state.copy(google = google) to ApiResult.Success(Unit)
        }
        if (result is ApiResult.Failure) return@withLock result
        receipts(keys, account)
    }

    override suspend fun setAutoExport(enabled: Boolean, calendarId: String?) = store.update { state ->
        if (enabled && state.google.account == null) return@update state to unauthenticated()
        if (enabled && state.google.calendars.none { it.id == calendarId && it.writable }) return@update state to invalid()
        state.copy(settings = state.settings.copy(googleAutoExport = enabled, googleAutoExportCalendarId = calendarId)) to ApiResult.Success(Unit)
    }

    override suspend fun syncExportedLocalEvent(localItemId: String) = updateExport(localItemId, false)
    override suspend fun deleteExportedLocalEvent(localItemId: String) = updateExport(localItemId, true)

    private suspend fun updateExport(id: String, remove: Boolean): ApiResult<ExportReceipt> = lock.withLock {
        val account = store.state.value.google.account ?: return@withLock unauthenticated()
        val result = store.update { state ->
            val entries = state.google.exports.filterValues { it.item.id == id }
            if (entries.isEmpty()) return@update state to notFound()
            if (entries.values.any { it.item.origin != Origin.USER }) return@update state to unsupported()
            val item = state.item(id)
            if (!remove && item == null) return@update state to notFound()
            val changed = entries.mapValues { (_, e) -> e.copy(item = item ?: e.item, deleting = remove, pending = true) }
            state.copy(google = state.google.copy(exports = state.google.exports + changed)) to ApiResult.Success(entries.keys.toList())
        }
        when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> receipts(result.data, account)
        }
    }

    private suspend fun receipts(keys: List<String>, account: String): ApiResult<ExportReceipt> {
        var first: ExportReceipt? = null
        for (key in keys) {
            val entry = store.state.value.google.exports.getValue(key)
            when (val result = call { send(key, account) }) {
                is ApiResult.Success -> if (first == null) first = ExportReceipt(entry.item.id, entry.eventId, ExportState.SENT, null)
                is ApiResult.Failure -> return if (result.retryable) ApiResult.Success(ExportReceipt(entry.item.id,
                    entry.eventId, ExportState.QUEUED, result.code)) else result
            }
        }
        return ApiResult.Success(checkNotNull(first))
    }

    private suspend fun send(key: String, account: String) {
        val entry = store.state.value.google.exports[key]?.takeIf { it.pending } ?: return
        remoteFor(account).send(entry)
        requireStored(store.update { state ->
            val exports = state.google.exports
            if (exports[key] != entry || state.google.account != account) return@update state to ApiResult.Success(Unit)
            val updated = if (entry.deleting) exports - key else exports + (key to entry.copy(pending = false))
            val cache = state.google.cache
            val nextCache = if (entry.deleting && entry.calendarId in cache) cache + (entry.calendarId to
                cache.getValue(entry.calendarId).filterNot { it.source?.externalId == entry.eventId }) else cache
            state.copy(google = state.google.copy(exports = updated, cache = nextCache)) to ApiResult.Success(Unit)
        })
    }

    private suspend fun <T> connected(action: suspend (String, GoogleRemote) -> T): ApiResult<T> {
        val account = store.state.value.google.account ?: return unauthenticated()
        val result = call { action(account, remoteFor(account)) }
        if (result is ApiResult.Failure && result.code == ApiErrorCode.AUTH_EXPIRED)
            store.update { it.copy(google = it.google.copy(expired = true)) to ApiResult.Success(Unit) }
        return result
    }

    private suspend fun <T> call(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: GoogleFailure) { ApiResult.Failure(e.code, e.retry) }
    catch (e: JsonParseException) { ApiResult.Failure(ApiErrorCode.PARSE_FAILED, false) }
    catch (e: DateTimeParseException) { ApiResult.Failure(ApiErrorCode.PARSE_FAILED, false) }
    catch (e: IllegalStateException) { if (e is CancellationException) throw e else ApiResult.Failure(ApiErrorCode.PARSE_FAILED, false) }

    private fun unauthenticated() = ApiResult.Failure(ApiErrorCode.UNAUTHENTICATED, false)
    private fun requireStored(result: ApiResult<Unit>) {
        if (result is ApiResult.Failure) throw GoogleFailure(result.code, result.retryable)
    }
    private fun CalendarItem.overlaps(range: DateRange) = startAt.date <= range.to && (endAt?.date ?: startAt.date) >= range.from
}
