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
import org.deepmatcha.mad.backend.school.LocalSchoolRepository
import org.deepmatcha.mad.backend.school.LocalSchoolSettings
import org.deepmatcha.mad.backend.school.SchoolAssignmentCalendar
import org.deepmatcha.mad.backend.school.SchoolAssignmentSource
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.SchoolSyncService
import org.deepmatcha.mad.backend.school.SyntheticSchoolSource
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.CalendarRepository
import org.deepmatcha.mad.contract.SchoolRepository
import org.deepmatcha.mad.contract.SettingsRepository
import org.deepmatcha.mad.contract.SyncService

/** File-backed school assignments and their calendar, settings, and synchronization entry points. */
class SchoolBackend private constructor(
    val school: SchoolRepository,
    val calendar: CalendarRepository,
    val sync: SyncService,
    private val localSettings: LocalSchoolSettings,
) {
    val settings: SettingsRepository get() = localSettings

    companion object {
        /** Opens the local backend with the built-in synthetic school source and schedules periodic sync. */
        suspend fun openSynthetic(context: Context): SchoolBackend =
            SyntheticSchoolRuntime.get(context).also { it.localSettings.ensureSchedule() }

        internal suspend fun open(
            file: File,
            source: SchoolAssignmentSource,
            scope: CoroutineScope,
            clock: Clock = Clock.systemUTC(),
            schedule: suspend (Int) -> Unit = {},
        ): SchoolBackend = withContext(Dispatchers.IO) {
            open(SchoolStore(file), source, scope, clock, schedule).also { it.localSettings.ensureSchedule() }
        }

        internal fun open(
            store: SchoolStore,
            source: SchoolAssignmentSource,
            scope: CoroutineScope,
            clock: Clock,
            schedule: suspend (Int) -> Unit,
        ) = SchoolBackend(
            school = LocalSchoolRepository(store),
            calendar = SchoolAssignmentCalendar(store, clock),
            sync = SchoolSyncService(store, source, scope, clock),
            localSettings = LocalSchoolSettings(store, schedule),
        )
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
            SchoolBackend.open(store, SyntheticSchoolSource, scope, Clock.systemUTC()) { interval ->
                withContext(Dispatchers.IO) { scheduleSchoolSync(appContext, interval) }
            }.also { backend = it }
        }
    }
}
