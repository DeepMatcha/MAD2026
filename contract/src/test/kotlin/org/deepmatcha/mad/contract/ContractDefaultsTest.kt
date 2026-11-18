package org.deepmatcha.mad.contract

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractDefaultsTest {
    @Test
    fun assignmentIsCompleteIfEitherCompletionSourceIsTrue() {
        val assignment = Assignment(
            id = "sample-assignment",
            courseId = "sample-course",
            title = "Sample assignment",
            description = null,
            submissionFormat = null,
            submissionMethod = null,
            gradingCriteria = null,
            availableAt = null,
            dueAt = null,
            schoolCompleted = false,
            userCompleted = false,
            hidden = false,
            source = null,
        )

        assertFalse(assignment.completed)
        assertTrue(assignment.copy(schoolCompleted = true).completed)
        assertTrue(assignment.copy(userCompleted = true).completed)
    }

    @Test
    fun dateOnlyValuesPreserveUnknownTime() {
        val date = LocalDate.of(2026, 11, 19)
        val dueAt = KstMoment(date, time = null)

        assertEquals(date, dueAt.date)
        assertNull(dueAt.time)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dateRangeRejectsAnEndBeforeItsStart() {
        val start = LocalDate.of(2026, 11, 19)
        DateRange(from = start, to = start.minusDays(1))
    }

    @Test
    fun nullablePatchDistinguishesClearingAValueFromLeavingItAlone() {
        val patch = LocalNotePatch(body = PatchField.Set(null))

        assertEquals(PatchField.Set<String?>(null), patch.body)
        assertSame(PatchField.Unset, patch.dateAt)
    }

    @Test
    fun settingsDefaultToHourlySyncAndIndependentOptIns() {
        val settings = AppSettings()

        assertTrue(settings.showCompletedItems)
        assertEquals(60, settings.syncIntervalMinutes)
        assertFalse(settings.autoAnalyze)
        assertFalse(settings.autoApplyAIResult)
        assertFalse(settings.googleAutoExport)
        assertNull(settings.googleAutoExportCalendarId)
    }

    @Test
    fun notificationsDefaultToEnabledWithOneHourDeadlineLeadTime() {
        val settings = NotificationSettings()

        assertTrue(settings.dueReminderEnabled)
        assertEquals(60, settings.dueReminderMinutes)
        assertTrue(settings.assignmentAvailableEnabled)
        assertTrue(settings.newAssignmentEnabled)
        assertTrue(settings.classChangeEnabled)
        assertTrue(settings.dateChangeApprovalNotificationEnabled)
    }
}
