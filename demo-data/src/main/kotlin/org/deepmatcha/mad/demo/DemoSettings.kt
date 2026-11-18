package org.deepmatcha.mad.demo

import kotlinx.coroutines.flow.MutableStateFlow
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.AppSettings
import org.deepmatcha.mad.contract.AppSettingsPatch
import org.deepmatcha.mad.contract.NotificationSettings
import org.deepmatcha.mad.contract.NotificationSettingsPatch
import org.deepmatcha.mad.contract.SettingsRepository

internal class DemoSettings : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())
    private val notifications = MutableStateFlow(NotificationSettings())

    override fun observeSettings() = settings

    override suspend fun updateSettings(patch: AppSettingsPatch): ApiResult<AppSettings> {
        val current = settings.value
        val updated = AppSettings(
            showCompletedItems = patch.showCompletedItems.or(current.showCompletedItems),
            syncIntervalMinutes = patch.syncIntervalMinutes.or(current.syncIntervalMinutes),
            autoAnalyze = patch.autoAnalyze.or(current.autoAnalyze),
            autoApplyAIResult = patch.autoApplyAIResult.or(current.autoApplyAIResult),
            googleAutoExport = patch.googleAutoExport.or(current.googleAutoExport),
            googleAutoExportCalendarId = patch.googleAutoExportCalendarId.or(current.googleAutoExportCalendarId),
        )
        if (updated.syncIntervalMinutes < 15) return invalidArgument()
        settings.value = updated
        return ApiResult.Success(updated)
    }

    override fun observeNotificationSettings() = notifications

    override suspend fun updateNotificationSettings(patch: NotificationSettingsPatch): ApiResult<NotificationSettings> {
        val current = notifications.value
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
        if (updated.dueReminderMinutes < 0) return invalidArgument()
        notifications.value = updated
        return ApiResult.Success(updated)
    }
}
