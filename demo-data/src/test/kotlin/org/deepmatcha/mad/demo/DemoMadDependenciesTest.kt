package org.deepmatcha.mad.demo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoMadDependenciesTest {
    @Test
    fun assignmentCompletionAndVisibilityAreSharedWithNotes() = runBlocking {
        val demo = DemoMadDependencies.create()
        val note = demo.notes.observeNotes().first().first {
            it.kind == NoteKind.SCHOOL_ASSIGNMENT && !it.completed
        }
        val assignmentId = checkNotNull(note.link).entityId
        val changed = demo.notes.setNoteCompleted(note.id, true)

        assertTrue(changed is ApiResult.Success && changed.data.completed)
        assertTrue(demo.school.observeAssignments().first().single { it.id == assignmentId }.completed)

        assertEquals(ApiResult.Success(Unit), demo.school.setSchoolItemHidden(note.id, true))
        assertFalse(demo.notes.observeNotes().first().any { it.id == note.id })
        assertTrue(demo.notes.observeNotes(includeHidden = true).first().single { it.id == note.id }.hidden)
    }

    @Test
    fun demoCalendarIncludesBothOverlappingWeekendClasses() = runBlocking {
        val demo = DemoMadDependencies.create()
        val saturday = LocalDate.now(ZoneId.of("Asia/Seoul")).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val occurrences = demo.calendar.observeCalendar(DateRange(saturday, saturday)).first()
            .filter { it.timetableEntryId != null }

        assertEquals(2, occurrences.size)
        assertEquals(2, occurrences.map { it.id }.distinct().size)
        assertEquals(ApiResult.Success(occurrences.first()), demo.calendar.getCalendarItem(occurrences.first().id))
    }

    @Test
    fun previewsKeepDateOnlyDeadlinesAndUndatedNotes() = runBlocking {
        val demo = DemoMadDependencies.create()
        val assignment = demo.school.observeAssignments().first().single { it.schoolCompleted }
        val note = demo.notes.observeNotes().first().first { it.kind == NoteKind.LOCAL }

        assertNull(checkNotNull(assignment.dueAt).time)
        assertNull(note.dateAt)
    }
}
