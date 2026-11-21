package org.deepmatcha.mad.backend

import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.backend.ai.OnDeviceTextEngine
import org.deepmatcha.mad.backend.school.SchoolStore
import org.deepmatcha.mad.backend.school.canvas.CanvasClient
import org.deepmatcha.mad.backend.school.canvas.CanvasSchoolSource
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Course

/** Read-only CAU eClass import for a caller with an approved, runtime-only Canvas credential. */
class CanvasSchoolBackend private constructor(
    private val store: SchoolStore,
    private val source: CanvasSchoolSource,
    backend: SchoolBackend,
) {
    val school = backend.school
    val sync = backend.sync
    val calendar = backend.calendar
    val analysis = backend.analysis
    private val rosterRefresh = Mutex()

    /** Explicitly refresh active student courses. A failed listing leaves the cached roster intact. */
    suspend fun refreshCourses(): ApiResult<List<Course>> = rosterRefresh.withLock {
        val records = when (val result = source.fetchCourses()) {
            is ApiResult.Failure -> return@withLock result
            is ApiResult.Success -> result.data
        }
        when (val saved = store.upsertCourses(records, replaceActiveRoster = true)) {
            is ApiResult.Failure -> saved
            is ApiResult.Success -> {
                val state = store.state.value
                val activeIds = checkNotNull(state.activeCourseExternalIds)
                ApiResult.Success(state.courses.values.map { it.course }.filter { it.source.externalId in activeIds })
            }
        }
    }

    companion object {
        /**
         * Opens cached data without contacting eClass. Use one backend instance and an app-private
         * store file per approved account. The caller supplies a credential for that same account
         * from its approved runtime secret source. A null value indicates no available credential.
         */
        suspend fun open(
            file: File,
            scope: CoroutineScope,
            credential: suspend () -> String?,
        ): CanvasSchoolBackend = open(file, scope, CanvasClient(credential))

        /** Uses the caller-provisioned model runtime when the on-device analysis mode is selected. */
        suspend fun open(
            file: File,
            scope: CoroutineScope,
            device: OnDeviceTextEngine,
            credential: suspend () -> String?,
        ): CanvasSchoolBackend = open(file, scope, CanvasClient(credential), device)

        internal suspend fun open(
            file: File,
            scope: CoroutineScope,
            client: CanvasClient,
            device: OnDeviceTextEngine? = null,
        ): CanvasSchoolBackend = withContext(Dispatchers.IO) {
            val store = SchoolStore(file)
            val source = CanvasSchoolSource(client)
            val backend = SchoolBackend.open(store, source, scope, Clock.systemUTC(), device, schedule = {})
            CanvasSchoolBackend(store, source, backend)
        }
    }
}
