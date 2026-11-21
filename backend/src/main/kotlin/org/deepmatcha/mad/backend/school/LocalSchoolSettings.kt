package org.deepmatcha.mad.backend.school

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.AppSettings
import org.deepmatcha.mad.contract.AppSettingsPatch
import org.deepmatcha.mad.contract.NotificationSettings
import org.deepmatcha.mad.contract.NotificationSettingsPatch
import org.deepmatcha.mad.contract.PatchField
import org.deepmatcha.mad.contract.SettingsRepository

internal class LocalSchoolSettings(
    private val store: SchoolStore,
    private val schedule: suspend (Int) -> Unit,
) : SettingsRepository {
    private val scheduleLock = Mutex()

    internal suspend fun ensureSchedule() = scheduleLock.withLock {
        schedule(store.state.value.settings.syncIntervalMinutes)
    }

    override fun observeSettings() = store.state.map { it.settings }.distinctUntilChanged()

    override suspend fun updateSettings(patch: AppSettingsPatch): ApiResult<AppSettings> = scheduleLock.withLock {
        // Once the interval is saved, keep the OS schedule in step even if the caller leaves.
        withContext(NonCancellable) {
            val result = store.update { state ->
                val current = state.settings
                val updated = AppSettings(
                    showCompletedItems = patch.showCompletedItems.or(current.showCompletedItems),
                    syncIntervalMinutes = patch.syncIntervalMinutes.or(current.syncIntervalMinutes),
                    autoAnalyze = patch.autoAnalyze.or(current.autoAnalyze),
                    autoApplyAIResult = patch.autoApplyAIResult.or(current.autoApplyAIResult),
                    googleAutoExport = patch.googleAutoExport.or(current.googleAutoExport),
                    googleAutoExportCalendarId = patch.googleAutoExportCalendarId.or(current.googleAutoExportCalendarId),
                )
                if (updated.syncIntervalMinutes < 15) {
                    return@update state to ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)
                }
                state.copy(settings = updated) to ApiResult.Success(updated)
            }
            if (result is ApiResult.Success && patch.syncIntervalMinutes is PatchField.Set) {
                try {
                    schedule(result.data.syncIntervalMinutes)
                } catch (_: IllegalStateException) {
                    return@withContext ApiResult.Failure(ApiErrorCode.INTERNAL, retryable = true)
                }
            }
            result
        }
    }

    override fun observeNotificationSettings() = store.state.map { it.notificationSettings }.distinctUntilChanged()

    override suspend fun updateNotificationSettings(patch: NotificationSettingsPatch): ApiResult<NotificationSettings> =
        store.update { state ->
            val current = state.notificationSettings
            val updated = NotificationSettings(
                dueReminderEnabled = patch.dueReminderEnabled.or(current.dueReminderEnabled),
                dueReminderMinutes = patch.dueReminderMinutes.or(current.dueReminderMinutes),
                assignmentAvailableEnabled = patch.assignmentAvailableEnabled.or(current.assignmentAvailableEnabled),
                newAssignmentEnabled = patch.newAssignmentEnabled.or(current.newAssignmentEnabled),
                classChangeEnabled = patch.classChangeEnabled.or(current.classChangeEnabled),
                dateChangeApprovalNotificationEnabled = patch.dateChangeApprovalNotificationEnabled.or(
                    current.dateChangeApprovalNotificationEnabled,
                ),
            )
            if (updated.dueReminderMinutes < 0) {
                return@update state to ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)
            }
            state.copy(notificationSettings = updated) to ApiResult.Success(updated)
        }
}
