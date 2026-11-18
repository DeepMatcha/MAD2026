package org.deepmatcha.mad.demo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.CalendarItem
import org.deepmatcha.mad.contract.CalendarKind
import org.deepmatcha.mad.contract.ClassMode
import org.deepmatcha.mad.contract.Course
import org.deepmatcha.mad.contract.Evidence
import org.deepmatcha.mad.contract.ExtractedItem
import org.deepmatcha.mad.contract.ExtractedKind
import org.deepmatcha.mad.contract.FolderKind
import org.deepmatcha.mad.contract.KstMoment
import org.deepmatcha.mad.contract.NoteFolder
import org.deepmatcha.mad.contract.NoteItem
import org.deepmatcha.mad.contract.NoteKind
import org.deepmatcha.mad.contract.NoteLink
import org.deepmatcha.mad.contract.NoteLinkedKind
import org.deepmatcha.mad.contract.Origin
import org.deepmatcha.mad.contract.PendingChange
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.Resource
import org.deepmatcha.mad.contract.ResourceKind
import org.deepmatcha.mad.contract.ReviewItem
import org.deepmatcha.mad.contract.ReviewReason
import org.deepmatcha.mad.contract.ReviewStatus
import org.deepmatcha.mad.contract.SourceRef
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.contract.SyncStatus
import org.deepmatcha.mad.contract.TimetableEntry

internal class DemoFixtures(val today: LocalDate) {
    private val checkedAt = Instant.now().minusSeconds(2_400)
    val idle = SyncStatus(SyncState.IDLE, null, null, null)
    val successful = SyncStatus(SyncState.SUCCESS, checkedAt, checkedAt, null)
    val unavailable = SyncStatus(SyncState.FAILED, checkedAt, null, ApiErrorCode.SOURCE_UNAVAILABLE)
    val partial = SyncStatus(SyncState.PARTIAL_SUCCESS, checkedAt, checkedAt, ApiErrorCode.SOURCE_UNAVAILABLE)

    private val androidCourse = Course(
        id("course-android"), "모바일 앱 개발", schoolSource("courses/10"), true, successful,
    )
    private val dataCourse = Course(
        id("course-data"), "데이터베이스", schoolSource("courses/20"), true, unavailable,
    )
    val courses = listOf(androidCourse, dataCourse)

    val assignments = listOf(
        Assignment(
            id = id("assignment-android"),
            courseId = androidCourse.id,
            title = "앱 시제품 제출",
            description = "시제품과 실행 방법을 제출합니다.",
            submissionFormat = "ZIP",
            submissionMethod = "eClass 과제 제출",
            gradingCriteria = null,
            availableAt = KstMoment(today.minusDays(2), LocalTime.of(9, 0)),
            dueAt = KstMoment(today.plusDays(2), LocalTime.of(18, 0)),
            schoolCompleted = false,
            userCompleted = false,
            hidden = false,
            source = schoolSource("courses/10/assignments/1"),
        ),
        Assignment(
            id = id("assignment-data"),
            courseId = dataCourse.id,
            title = "SQL 연습 문제",
            description = null,
            submissionFormat = null,
            submissionMethod = null,
            gradingCriteria = null,
            availableAt = null,
            dueAt = KstMoment(today.plusDays(1), null),
            schoolCompleted = true,
            userCompleted = false,
            hidden = false,
            source = schoolSource("courses/20/assignments/2"),
        ),
    )

    private val notice = Resource(
        id = id("notice-android"),
        courseId = androidCourse.id,
        kind = ResourceKind.NOTICE,
        title = "다음 발표 조 편성 안내",
        contentText = "발표용 조 편성은 다음 수업에서 안내합니다.",
        source = schoolSource("courses/10/announcements/3"),
        fileInfo = null,
    )
    val resources = listOf(notice)

    val folders = courses.map { course ->
        NoteFolder(id("folder-${course.id}"), FolderKind.SCHOOL, course.id, course.name, course.name)
    }

    val notes = listOf(
        NoteItem(
            id = id("note-local"),
            kind = NoteKind.LOCAL,
            courseId = null,
            folderId = null,
            title = "발표 자료 준비",
            body = "실행 화면을 캡처하고 조원에게 공유하기.",
            dateAt = null,
            completed = false,
            hidden = false,
            link = null,
            source = null,
        ),
        NoteItem(
            id = id("note-notice"),
            kind = NoteKind.SCHOOL_RESOURCE,
            courseId = notice.courseId,
            folderId = folders.first().id,
            title = notice.title,
            body = notice.contentText,
            dateAt = null,
            completed = false,
            hidden = false,
            link = NoteLink(NoteLinkedKind.RESOURCE, notice.id),
            source = notice.source,
        ),
    )

    val timetable = listOf(
        TimetableEntry(
            id("weekend-entry"), androidCourse.id, "모바일 앱 개발 실습",
            DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), "실습실 302", null, null,
        ),
        TimetableEntry(
            id("weekend-overlap"), null, "팀 과제 실습",
            DayOfWeek.SATURDAY, LocalTime.of(11, 0), LocalTime.of(13, 0), null, null, null,
        ),
    )

    private val lecture = CalendarItem(
        id = id("lecture-online"),
        origin = Origin.SCHOOL,
        kind = CalendarKind.LECTURE,
        title = "데이터베이스 강의",
        description = null,
        startAt = KstMoment(today, LocalTime.of(14, 0)),
        endAt = KstMoment(today, LocalTime.of(15, 0)),
        courseId = dataCourse.id,
        assignmentId = null,
        classMode = ClassMode.ONLINE,
        location = null,
        timetableEntryId = null,
        completed = null,
        hidden = false,
        source = schoolSource("courses/20/lectures/10"),
    )
    val googleEvent = CalendarItem(
        id = id("google-study"),
        origin = Origin.GOOGLE,
        kind = CalendarKind.EVENT,
        title = "스터디",
        description = null,
        startAt = KstMoment(today.plusDays(1), LocalTime.of(17, 0)),
        endAt = KstMoment(today.plusDays(1), LocalTime.of(18, 0)),
        courseId = null,
        assignmentId = null,
        classMode = null,
        location = null,
        timetableEntryId = null,
        completed = null,
        hidden = false,
        source = SourceRef(Provider.GOOGLE_CALENDAR, "demo-calendar-event", null, checkedAt),
    )
    val events = listOf(lecture, googleEvent)

    val pending = PendingChange(
        id("pending-lecture"), lecture.id, "startAt", today.toString(), today.plusDays(1).toString(),
        lecture.source,
    )

    val evidence = Evidence(notice.source.url, notice.contentText, null, null)
    val candidate = ExtractedItem(
        kind = ExtractedKind.EVENT,
        title = "발표 조 편성",
        description = null,
        submissionFormat = null,
        submissionMethod = null,
        gradingCriteria = null,
        availableAt = null,
        dueAt = null,
        evidence = listOf(evidence),
    )
    val review = ReviewItem(
        id("notice-review"), notice.id, ReviewReason.INCOMPLETE, listOf(candidate), ReviewStatus.OPEN,
    )

    private fun schoolSource(path: String) =
        SourceRef(Provider.SCHOOL, path.substringAfterLast('/'), "https://school.example.invalid/$path", checkedAt)

    private fun id(name: String): String = UUID.nameUUIDFromBytes("mad-demo:$name".toByteArray()).toString()
}
