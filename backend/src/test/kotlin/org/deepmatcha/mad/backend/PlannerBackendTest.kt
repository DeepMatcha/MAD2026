package org.deepmatcha.mad.backend

import com.google.gson.JsonParser
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.deepmatcha.mad.backend.google.*
import org.deepmatcha.mad.backend.school.*
import org.deepmatcha.mad.backend.school.canvas.CanvasClient
import org.deepmatcha.mad.backend.school.canvas.CanvasResponse
import org.deepmatcha.mad.contract.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlannerBackendTest {
    @get:Rule val temp = TemporaryFolder()
    private val file get() = File(temp.root, "school.json")
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val saturday = LocalDate.of(2026, 11, 7)
    private fun <T> ApiResult<T>.ok() = (this as ApiResult.Success<T>).data

    @Test fun weekendOccurrencesAreStableOverlapAndExpire() = runBlocking {
        val store = SchoolStore(file)
        val timetable = LocalTimetable(store)
        val calendar = SchoolAssignmentCalendar(store, clock)
        val input = TimetableEntryInput(null, "Lab", DayOfWeek.SATURDAY, LocalTime.NOON,
            LocalTime.of(13, 0), "A", saturday, saturday.plusWeeks(1))
        val entry = timetable.createTimetableEntry(input).ok()
        timetable.createTimetableEntry(input.copy(title = "Seminar")).ok()
        val range = DateRange(saturday.minusDays(7), saturday.plusDays(14))
        val items = calendar.observeCalendar(range).first()
        assertEquals(4, items.size)
        val first = items.first { it.timetableEntryId == entry.id }
        assertEquals(first, calendar.getCalendarItem(first.id).ok())
        assertEquals(ApiErrorCode.UNSUPPORTED, (calendar.updateCalendarItem(first.id, CalendarItemPatch()) as ApiResult.Failure).code)
        assertEquals(ApiErrorCode.UNSUPPORTED, (calendar.deleteLocalEvent(first.id) as ApiResult.Failure).code)
        assertEquals(ApiErrorCode.INVALID_ARGUMENT, (timetable.updateTimetableEntry(entry.id,
            TimetableEntryPatch(endTime = PatchField.Set(LocalTime.NOON))) as ApiResult.Failure).code)
        assertEquals(entry, LocalTimetable(SchoolStore(file)).getTimetableEntry(entry.id).ok())
        timetable.deleteTimetableEntry(entry.id).ok()
        assertEquals(2, calendar.observeCalendar(range).first().size)
    }

    @Test fun legacySnapshotNotesAndSchoolChangesShareOneState() = runBlocking {
        val seed = SchoolStore(file)
        seed.upsertCourses(listOf(SchoolCourseRecord("A", "Algorithms"))).ok()
        val snapshot = JsonParser.parseString(file.readText()).asJsonObject
        listOf("planner", "alerts", "google").forEach { snapshot.getAsJsonObject("state").remove(it) }
        file.writeText(snapshot.toString())
        val store = SchoolStore(file)
        val course = store.state.value.courses.keys.single()
        val notes = LocalNotes(store)
        val school = LocalSchoolRepository(store)
        val record = SchoolAssignmentRecord("remote", null, null, "Paper", null, null, null, null,
            null, KstMoment(saturday, null), false)
        store.update { it.applyAssignmentDelta(course, null, AssignmentDelta(listOf(record), emptySet(),
            AssignmentCheckpoint("1")), clock.instant()) }.ok()
        val assignment = notes.observeNotes().first().single()
        notes.setNoteCompleted(assignment.id, true).ok()
        assertTrue(school.getAssignment(assignment.link!!.entityId).ok().completed)
        val memo = notes.createLocalNote(LocalNoteInput("Read", null, course, dateAt = null)).ok()
        val folder = notes.createNoteFolder("Personal").ok()
        notes.renameNoteFolder(course, "CS").ok()
        notes.updateLocalNote(memo.id, LocalNotePatch(folderId = PatchField.Set(folder.id))).ok()
        assertNull(notes.getNote(memo.id).ok().courseId)
        assertEquals(listOf(memo.id), notes.observeNotes(courseId = course, folderId = folder.id).first().map { it.id })
        school.setSchoolItemHidden(assignment.id, true).ok()
        assertEquals(0, notes.observeNoteCourseSummaries().first().single { it.courseId == course }.noteCount)
        store.upsertCourses(listOf(SchoolCourseRecord("A", "Advanced algorithms"))).ok()
        val reopened = LocalNotes(SchoolStore(file))
        val summary = reopened.observeNoteCourseSummaries().first().single { it.courseId == course }
        assertEquals("CS", summary.displayName)
        assertEquals("Advanced algorithms", summary.courseName)
        store.update { it.applyAssignmentDelta(course, AssignmentCheckpoint("1"), AssignmentDelta(emptyList(), setOf("remote"),
            AssignmentCheckpoint("2")), clock.instant()) }.ok()
        assertEquals(listOf(memo.id), notes.observeNotes(includeHidden = true).first().map { it.id })
        notes.deleteLocalNote(memo.id).ok()
    }

    @Test fun noticesKeepOverridesAcrossRefreshAndDoNotEraseMemosOnDeletion() = runBlocking {
        var reply = CanvasResponse(200, """[{"id":2,"title":"Office hours","message":"Bring notes"}]""")
        val client = CanvasClient({ "synthetic" }) { url, _ ->
            when (url.path) {
                "/api/v1/courses" -> CanvasResponse(200, """[{"id":1,"name":"Lab"}]""")
                "/api/v1/courses/1/discussion_topics" -> reply
                else -> error("unexpected request")
            }
        }
        val backend = CanvasSchoolBackend.open(file, this, client)
        val course = backend.refreshCourses().ok().single().id
        backend.refreshNotices(course).ok()
        val note = backend.notes.observeNotes().first().single()
        assertEquals(NoteKind.SCHOOL_RESOURCE, note.kind)
        assertNull(note.dateAt)
        assertEquals(ResourceKind.NOTICE, backend.school.getResource(note.link!!.entityId).ok().kind)
        backend.notes.setNoteCompleted(note.id, true).ok()
        backend.school.setSchoolItemHidden(note.id, true).ok()
        reply = CanvasResponse(503, "")
        assertTrue(backend.refreshNotices(course) is ApiResult.Failure)
        assertTrue(backend.notes.getNote(note.id).ok().completed)
        assertTrue(backend.notes.observeNotes().first().isEmpty())
        val memo = backend.notes.createLocalNote(LocalNoteInput("Memo", null, course, dateAt = null)).ok()
        reply = CanvasResponse(200, "[]")
        backend.refreshNotices(course).ok()
        assertEquals(listOf(memo.id), backend.notes.observeNotes(includeHidden = true).first().map { it.id })
    }

    @Test fun timedAndImmediateAlertsFollowSettingsApprovalAndCompletion() = runBlocking {
        val store = SchoolStore(file)
        store.upsertCourses(listOf(SchoolCourseRecord("A", "Lab"))).ok()
        val course = store.state.value.courses.keys.single()
        val due = KstMoment(saturday, LocalTime.NOON)
        val record = SchoolAssignmentRecord("remote", null, null, "Lab", null, null, null, null,
            due.copy(date = saturday.minusDays(1)), due, false)
        suspend fun apply(previous: String?, next: String, updated: SchoolAssignmentRecord) {
            store.update { it.applyAssignmentDelta(course, previous?.let(::AssignmentCheckpoint),
                AssignmentDelta(listOf(updated), emptySet(), AssignmentCheckpoint(next)), clock.instant()) }.ok()
        }
        apply(null, "1", record)
        val id = store.state.value.assignments.keys.single()
        fun plan() = store.state.value.notificationPlan()
        assertEquals(setOf(AlertKind.NEW, AlertKind.DUE, AlertKind.AVAILABLE), plan().map { it.kind }.toSet())
        assertEquals(saturday.atTime(11, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),
            plan().single { it.kind == AlertKind.DUE }.at)
        apply("1", "2", record.copy(dueAt = due.copy(time = null)))
        assertTrue(plan().any { it.kind == AlertKind.APPROVAL })
        val calendar = SchoolAssignmentCalendar(store, clock)
        calendar.resolvePendingChange(calendar.observePendingChanges().first().single().id, true).ok()
        assertFalse(plan().any { it.kind == AlertKind.APPROVAL || it.kind == AlertKind.DUE })
        calendar.updateCalendarItem(id, CalendarItemPatch(classMode = PatchField.Set(ClassMode.ONLINE))).ok()
        assertTrue(plan().any { it.kind == AlertKind.CLASS_CHANGE })
        LocalSchoolSettings(store) {}.updateNotificationSettings(NotificationSettingsPatch(
            classChangeEnabled = PatchField.Set(false))).ok()
        assertFalse(plan().any { it.kind == AlertKind.CLASS_CHANGE })
        LocalSchoolRepository(store).setUserCompleted(id, true).ok()
        assertTrue(plan().isEmpty())
    }

    @Test fun exportsRetryWithOneRemoteIdAndDeleteSurvivesRestart() = runBlocking {
        val store = SchoolStore(file)
        val info = GoogleCalendarInfo("cal", "Calendar", true, true)
        store.update { it.copy(google = GoogleState(account = "test", calendars = listOf(info))) to ApiResult.Success(Unit) }.ok()
        val remote = FakeGoogle(info)
        val google = GoogleCalendarService(store, null) { remote }
        val calendar = SchoolAssignmentCalendar(store, clock)
        val input = LocalEventInput("Appointment", null, CalendarKind.EVENT, KstMoment(saturday, null), null, null, null, null)
        val manual = calendar.createLocalEvent(input).ok()
        assertTrue(store.state.value.google.exports.isEmpty())
        google.setAutoExport(true, info.id).ok()
        val auto = calendar.createLocalEvent(input.copy(title = "Meeting")).ok()
        assertEquals(auto.id, store.state.value.google.exports.values.single().item.id)
        remote.offline = true
        assertEquals(ExportState.QUEUED, google.exportToGoogle(listOf(manual.id), info.id).ok().state)
        val mapping = store.state.value.google.exports.values.single { it.item.id == manual.id }.eventId
        val range = DateRange(saturday, saturday)
        assertTrue(google.observeGoogleEvents(info.id, range).first().isEmpty())
        assertEquals(0, remote.reads)
        remote.offline = false
        google.refresh(range).ok()
        assertEquals(2, remote.items.size)
        calendar.updateCalendarItem(manual.id, CalendarItemPatch(title = PatchField.Set("Moved"))).ok()
        assertEquals(mapping, google.syncExportedLocalEvent(manual.id).ok().googleEventId)
        assertEquals("Moved", remote.items.getValue(mapping).title)
        assertEquals(ExportState.QUEUED, calendar.deleteLocalEvent(manual.id).ok().remoteState)
        val reopened = SchoolStore(file)
        GoogleCalendarService(reopened, null) { remote }.refresh(range).ok()
        assertEquals(1, remote.items.size)
        assertFalse(mapping in remote.items)
        assertTrue(reopened.state.value.google.exports.values.none { it.pending })
    }

    private class FakeGoogle(val info: GoogleCalendarInfo) : GoogleRemote {
        var offline = false
        var reads = 0
        val items = mutableMapOf<String, CalendarItem>()
        override suspend fun calendars() = listOf(info)
        override suspend fun events(calendar: String, range: DateRange): List<CalendarItem> {
            reads++
            return emptyList()
        }
        override suspend fun send(entry: GoogleExport) {
            if (offline) throw GoogleFailure(ApiErrorCode.NETWORK, true)
            if (entry.deleting) items.remove(entry.eventId) else items[entry.eventId] = entry.item
        }
    }
}
