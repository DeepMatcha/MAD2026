package org.deepmatcha.mad.backend

import android.content.Context
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.backend.ai.LocalAnalysisService
import org.deepmatcha.mad.backend.ai.OnDeviceTextEngine
import org.deepmatcha.mad.backend.google.CalendarSync
import org.deepmatcha.mad.backend.google.GoogleCalendarService
import org.deepmatcha.mad.backend.notifications.PlannerAlarmReceiver
import org.deepmatcha.mad.backend.school.LocalNotes
import org.deepmatcha.mad.backend.school.LocalSchoolRepository
import org.deepmatcha.mad.backend.school.LocalSchoolSettings
import org.deepmatcha.mad.backend.school.LocalTimetable
import org.deepmatcha.mad.backend.school.SchoolAssignmentCalendar
import org.deepmatcha.mad.backend.school.SchoolAssignmentSource
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.SchoolSyncService
import org.deepmatcha.mad.backend.school.SyntheticSchoolSource
import org.deepmatcha.mad.backend.school.notificationPlan
import org.deepmatcha.mad.contract.AnalysisService
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.CalendarRepository
import org.deepmatcha.mad.contract.DateRange
import org.deepmatcha.mad.contract.GoogleCalendarRepository
import org.deepmatcha.mad.contract.IntegrationsService
import org.deepmatcha.mad.contract.MadDependencies
import org.deepmatcha.mad.contract.NotesRepository
import org.deepmatcha.mad.contract.SchoolRepository
import org.deepmatcha.mad.contract.SettingsRepository
import org.deepmatcha.mad.contract.SyncService
import org.deepmatcha.mad.contract.TimetableRepository

/** File-backed school assignments and their calendar, settings, and synchronization entry points. */
class SchoolBackend private constructor(
    override val school: SchoolRepository,
    override val calendar: CalendarRepository,
    override val sync: SyncService,
    override val analysis: AnalysisService,
    override val timetable: TimetableRepository,
    override val notes: NotesRepository,
    private val google: GoogleCalendarService,
    private val localSettings: LocalSchoolSettings,
    private val store: SchoolStore,
) : MadDependencies {
    override val settings: SettingsRepository get() = localSettings
    override val googleCalendar: GoogleCalendarRepository get() = google
    override val integrations: IntegrationsService get() = google

    suspend fun refreshGoogleEvents(range: DateRange): ApiResult<Unit> = google.refresh(range)

    /** Call after returning from Android's notification permission or channel settings screen. */
    suspend fun refreshNotificationDelivery() = withContext(Dispatchers.IO) { store.refreshNotificationDelivery() }

    companion object {
        /** Opens the local backend with the built-in synthetic school source and schedules periodic sync. */
        suspend fun openSynthetic(context: Context): SchoolBackend =
            SyntheticSchoolRuntime.get(context).also { it.localSettings.ensureSchedule() }

        internal suspend fun open(
            file: File,
            source: SchoolAssignmentSource,
            scope: CoroutineScope,
            clock: Clock = Clock.systemUTC(),
            device: OnDeviceTextEngine? = null,
            schedule: suspend (Int) -> Unit = {},
        ): SchoolBackend = withContext(Dispatchers.IO) {
            open(SchoolStore(file), source, scope, clock, device, schedule = schedule).also { it.localSettings.ensureSchedule() }
        }

        internal fun open(
            store: SchoolStore,
            source: SchoolAssignmentSource,
            scope: CoroutineScope,
            clock: Clock,
            device: OnDeviceTextEngine? = null,
            context: Context? = null,
            schedule: suspend (Int) -> Unit,
        ): SchoolBackend {
            val school = LocalSchoolRepository(store)
            val google = GoogleCalendarService(store, context)
            if (context != null) {
                google.processPending(scope)
                store.onPlannerChange { state -> PlannerAlarmReceiver.reconcile(context, store.namespace, state.notificationPlan()) }
            }
            return SchoolBackend(
                school = school,
                calendar = SchoolAssignmentCalendar(store, clock),
                sync = CalendarSync(SchoolSyncService(store, source, scope, clock), google, scope, clock),
                timetable = LocalTimetable(store),
                notes = LocalNotes(store),
                google = google,
                analysis = LocalAnalysisService(store, school, scope, clock, device),
                localSettings = LocalSchoolSettings(store, schedule),
                store = store,
            )
        }
    }
}

internal object SyntheticSchoolRuntime {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backend: SchoolBackend? = null

    suspend fun get(context: Context): SchoolBackend = lock.withLock {
        backend ?: withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val store = SchoolStore(File(appContext.noBackupFilesDir, "school-synthetic-v1.json"))
            val seeded = store.upsertCourses(SyntheticSchoolSource.courses)
            check(seeded is ApiResult.Success) { "Could not initialize the local school store" }
            SchoolBackend.open(store, SyntheticSchoolSource, scope, Clock.systemUTC(), context = appContext) { interval ->
                withContext(Dispatchers.IO) { scheduleSchoolSync(appContext, interval) }
            }.also { backend = it }
        }
    }
}
