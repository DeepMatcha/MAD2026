package org.deepmatcha.mad.contract

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class Origin { USER, SCHOOL, GOOGLE }
enum class CalendarKind { ASSIGNMENT, CLASS, EVENT, LECTURE }
enum class ClassMode { NORMAL, ONLINE, CANCELLED }

data class CalendarItem(
    val id: String,
    val origin: Origin,
    val kind: CalendarKind,
    val title: String,
    val description: String?,
    val startAt: KstMoment,
    val endAt: KstMoment?,
    val courseId: String?,
    val assignmentId: String?,
    val classMode: ClassMode?,
    val location: String?,
    val timetableEntryId: String?,
    val completed: Boolean?,
    val hidden: Boolean,
    val source: SourceRef?,
)

data class LocalEventInput(
    val title: String,
    val description: String?,
    val kind: CalendarKind,
    val startAt: KstMoment,
    val endAt: KstMoment?,
    val courseId: String?,
    val classMode: ClassMode?,
    val location: String?,
)

data class CalendarItemPatch(
    val title: PatchField<String> = PatchField.Unset,
    val description: PatchField<String?> = PatchField.Unset,
    val startAt: PatchField<KstMoment> = PatchField.Unset,
    val endAt: PatchField<KstMoment?> = PatchField.Unset,
    val classMode: PatchField<ClassMode?> = PatchField.Unset,
    val completed: PatchField<Boolean?> = PatchField.Unset,
)

data class TimetableEntry(
    val id: String,
    val courseId: String?,
    val title: String,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String?,
    val validFrom: LocalDate?,
    val validUntil: LocalDate?,
)

data class TimetableEntryInput(
    val courseId: String?,
    val title: String,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val location: String?,
    val validFrom: LocalDate?,
    val validUntil: LocalDate?,
)

data class TimetableEntryPatch(
    val courseId: PatchField<String?> = PatchField.Unset,
    val title: PatchField<String> = PatchField.Unset,
    val dayOfWeek: PatchField<DayOfWeek> = PatchField.Unset,
    val startTime: PatchField<LocalTime> = PatchField.Unset,
    val endTime: PatchField<LocalTime> = PatchField.Unset,
    val location: PatchField<String?> = PatchField.Unset,
    val validFrom: PatchField<LocalDate?> = PatchField.Unset,
    val validUntil: PatchField<LocalDate?> = PatchField.Unset,
)

data class PendingChange(
    val id: String,
    val itemId: String,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val source: SourceRef?,
)

data class WorkSummary(
    val total: Int,
    val completed: Int,
    val remaining: Int,
    val upcoming: Int,
)
