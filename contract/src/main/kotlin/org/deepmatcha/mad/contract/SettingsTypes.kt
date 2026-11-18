package org.deepmatcha.mad.contract

data class AppSettings(
    val showCompletedItems: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    val autoAnalyze: Boolean = false,
    val autoApplyAIResult: Boolean = false,
    val googleAutoExport: Boolean = false,
    val googleAutoExportCalendarId: String? = null,
)

data class AppSettingsPatch(
    val showCompletedItems: PatchField<Boolean> = PatchField.Unset,
    val syncIntervalMinutes: PatchField<Int> = PatchField.Unset,
    val autoAnalyze: PatchField<Boolean> = PatchField.Unset,
    val autoApplyAIResult: PatchField<Boolean> = PatchField.Unset,
    val googleAutoExport: PatchField<Boolean> = PatchField.Unset,
    val googleAutoExportCalendarId: PatchField<String?> = PatchField.Unset,
)

data class NotificationSettings(
    val dueReminderEnabled: Boolean = true,
    val dueReminderMinutes: Int = 60,
    val assignmentAvailableEnabled: Boolean = true,
    val newAssignmentEnabled: Boolean = true,
    val classChangeEnabled: Boolean = true,
    val dateChangeApprovalNotificationEnabled: Boolean = true,
)

data class NotificationSettingsPatch(
    val dueReminderEnabled: PatchField<Boolean> = PatchField.Unset,
    val dueReminderMinutes: PatchField<Int> = PatchField.Unset,
    val assignmentAvailableEnabled: PatchField<Boolean> = PatchField.Unset,
    val newAssignmentEnabled: PatchField<Boolean> = PatchField.Unset,
    val classChangeEnabled: PatchField<Boolean> = PatchField.Unset,
    val dateChangeApprovalNotificationEnabled: PatchField<Boolean> = PatchField.Unset,
)
