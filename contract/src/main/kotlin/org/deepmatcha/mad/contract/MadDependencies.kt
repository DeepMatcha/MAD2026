package org.deepmatcha.mad.contract

import kotlinx.coroutines.flow.Flow

/** The app's local data and service entry points. UI code depends on these interfaces. */
interface MadDependencies {
    val school: SchoolRepository
    val calendar: CalendarRepository
    val timetable: TimetableRepository
    val notes: NotesRepository
    val integrations: IntegrationsService
    val sync: SyncService
    val analysis: AnalysisService
    val googleCalendar: GoogleCalendarRepository
    val settings: SettingsRepository
}

/** Observations emit local state first. Subscribing does not start a remote sync. */
interface SchoolRepository {
    fun observeCourses(): Flow<List<Course>>
    suspend fun getCourse(courseId: String): ApiResult<Course>
    suspend fun setCourseSyncEnabled(courseId: String, enabled: Boolean): ApiResult<Unit>

    fun observeAssignments(
        courseId: String? = null,
        includeCompleted: Boolean = true,
        includeHidden: Boolean = false,
    ): Flow<List<Assignment>>

    suspend fun getAssignment(assignmentId: String): ApiResult<Assignment>
    suspend fun setUserCompleted(assignmentId: String, completed: Boolean): ApiResult<Assignment>
    suspend fun setSchoolItemHidden(itemId: String, hidden: Boolean): ApiResult<Unit>
    fun observeResources(courseId: String, kinds: Set<ResourceKind>? = null): Flow<List<Resource>>
    suspend fun getResource(resourceId: String): ApiResult<Resource>
    suspend fun getSource(itemId: String): ApiResult<SourceRef?>
}

interface CalendarRepository {
    /** A null filter uses the user's setting. The requested dates include both endpoints. */
    fun observeCalendar(range: DateRange, includeCompleted: Boolean? = null): Flow<List<CalendarItem>>

    suspend fun getCalendarItem(itemId: String): ApiResult<CalendarItem>
    suspend fun createLocalEvent(input: LocalEventInput): ApiResult<CalendarItem>
    suspend fun updateCalendarItem(itemId: String, patch: CalendarItemPatch): ApiResult<CalendarItem>
    suspend fun deleteLocalEvent(itemId: String): ApiResult<DeleteReceipt>
    fun observePendingChanges(): Flow<List<PendingChange>>
    suspend fun resolvePendingChange(changeId: String, approve: Boolean): ApiResult<Unit>
    fun observeWorkSummary(range: DateRange): Flow<WorkSummary>
}

/** Recurring classes are edited here. Their dated calendar occurrences are projections. */
interface TimetableRepository {
    fun observeTimetableEntries(): Flow<List<TimetableEntry>>
    suspend fun getTimetableEntry(entryId: String): ApiResult<TimetableEntry>
    suspend fun createTimetableEntry(input: TimetableEntryInput): ApiResult<TimetableEntry>
    suspend fun updateTimetableEntry(entryId: String, patch: TimetableEntryPatch): ApiResult<TimetableEntry>
    suspend fun deleteTimetableEntry(entryId: String): ApiResult<Unit>
}

interface NotesRepository {
    fun observeNoteCourseSummaries(): Flow<List<NoteCourseSummary>>

    /** A specified folder takes precedence over the course filter. */
    fun observeNotes(
        courseId: String? = null,
        includeCompleted: Boolean = true,
        includeHidden: Boolean = false,
        folderId: String? = null,
    ): Flow<List<NoteItem>>

    suspend fun getNote(noteId: String): ApiResult<NoteItem>
    suspend fun createLocalNote(input: LocalNoteInput): ApiResult<NoteItem>
    suspend fun updateLocalNote(noteId: String, patch: LocalNotePatch): ApiResult<NoteItem>
    suspend fun deleteLocalNote(noteId: String): ApiResult<Unit>
    suspend fun setNoteCompleted(noteId: String, completed: Boolean): ApiResult<NoteItem>
    suspend fun createNoteFolder(name: String): ApiResult<NoteFolder>
    suspend fun renameNoteFolder(folderId: String, displayName: String): ApiResult<NoteFolder>
}

interface IntegrationsService {
    fun observeIntegrations(): Flow<List<IntegrationStatus>>
    suspend fun connectSchool(studentId: String, password: String): ApiResult<IntegrationStatus>
    suspend fun refreshSchoolAuth(): ApiResult<IntegrationStatus>
    suspend fun beginGoogleAuth(): ApiResult<AuthLaunchRequest>
    suspend fun completeGoogleAuth(result: GoogleAuthResult): ApiResult<IntegrationStatus>
    suspend fun disconnectGoogle(): ApiResult<Unit>
    suspend fun testIntegration(provider: Provider): ApiResult<ConnectionTestResult>
}

interface SyncService {
    /** A null course list selects all enabled courses. The returned ID identifies the job. */
    suspend fun startSync(
        provider: Provider = Provider.SCHOOL,
        courseIds: List<String>? = null,
    ): ApiResult<String>

    fun observeSyncJob(jobId: String): Flow<JobState>
    fun observeSyncStatus(provider: Provider): Flow<SyncStatus>
    fun observeCourseSyncStatus(courseId: String): Flow<SyncStatus>
    suspend fun cancelSync(jobId: String): ApiResult<Unit>
    suspend fun retrySync(jobId: String): ApiResult<String>
}

interface AnalysisService {
    suspend fun getAIConfig(): ApiResult<AIConfigPublic>
    suspend fun setAIConfig(baseUrl: String, apiKey: String, modelId: String? = null): ApiResult<Unit>
    suspend fun testAIConnection(): ApiResult<ConnectionTestResult>
    suspend fun analyzeResource(resourceId: String): ApiResult<String>
    fun observeAnalysisJob(jobId: String): Flow<JobState>
    suspend fun getAnalysisResult(jobId: String): ApiResult<AnalysisResult>
    fun observeManualReviews(status: ReviewStatus? = ReviewStatus.OPEN): Flow<List<ReviewItem>>
    suspend fun resolveManualReview(reviewId: String, decision: ManualReviewDecision): ApiResult<Unit>
}

interface GoogleCalendarRepository {
    suspend fun getGoogleCalendars(): ApiResult<List<GoogleCalendarInfo>>
    fun observeGoogleEvents(calendarId: String, range: DateRange): Flow<List<CalendarItem>>
    suspend fun exportToGoogle(localItemIds: List<String>, calendarId: String): ApiResult<ExportReceipt>
    suspend fun setAutoExport(enabled: Boolean, calendarId: String?): ApiResult<Unit>
    suspend fun syncExportedLocalEvent(localItemId: String): ApiResult<ExportReceipt>
    suspend fun deleteExportedLocalEvent(localItemId: String): ApiResult<ExportReceipt>
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(patch: AppSettingsPatch): ApiResult<AppSettings>
    fun observeNotificationSettings(): Flow<NotificationSettings>
    suspend fun updateNotificationSettings(patch: NotificationSettingsPatch): ApiResult<NotificationSettings>
}
