package org.deepmatcha.mad.backend.school

import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Course
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.SourceRef
import org.deepmatcha.mad.contract.SyncState
import org.deepmatcha.mad.contract.SyncStatus

/** A single process-owned store per file. Publish changes only after the snapshot is committed. */
internal class SchoolStore(file: File) : SchoolAssignmentSyncStore {
    private val path = file.absoluteFile.toPath()
    val namespace: String = UUID.nameUUIDFromBytes(path.toString().toByteArray()).toString()
    private val writer = Mutex()
    private val mutableState = MutableStateFlow(readSnapshot().recoverInterruptedSync().let { state ->
        state.copy(analysis = state.analysis.cancelActiveJobs())
    })
    val state: StateFlow<SchoolState> = mutableState.asStateFlow()
    private var notifyPlanner: ((SchoolState) -> Unit)? = null

    fun onPlannerChange(listener: (SchoolState) -> Unit) {
        notifyPlanner = listener
        listener(state.value)
    }

    suspend fun refreshNotificationDelivery() = writer.withLock { notifyPlanner?.invoke(state.value) ?: Unit }

    /**
     * Commits the returned state and then returns the command result. A failure may still
     * update bookkeeping, such as the status of a course whose delta could not be applied.
     */
    suspend fun <T> update(
        transform: (SchoolState) -> Pair<SchoolState, ApiResult<T>>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        writer.withLock {
            val current = mutableState.value
            val (changed, result) = transform(current)
            val next = changed.queueCalendarEdits(current).recordAlerts(current, Instant.now())
            if (next != current) {
                currentCoroutineContext().ensureActive()
                try {
                    writeSnapshot(next)
                } catch (_: IOException) {
                    return@withLock ApiResult.Failure(ApiErrorCode.INTERNAL, retryable = true)
                } catch (_: JsonIOException) {
                    return@withLock ApiResult.Failure(ApiErrorCode.INTERNAL, retryable = true)
                }
                mutableState.value = next
                if (next.alerts !== current.alerts || next.assignments !== current.assignments ||
                    next.planner.events !== current.planner.events || next.notificationSettings != current.notificationSettings)
                    notifyPlanner?.invoke(next)
            }
            result
        }
    }

    suspend fun upsertCourses(
        records: List<SchoolCourseRecord>,
        replaceActiveRoster: Boolean = false,
    ): ApiResult<Unit> {
        val ids = records.map(SchoolCourseRecord::externalId)
        if (ids.toSet().size != ids.size || ids.any(String::isBlank)) {
            return ApiResult.Failure(ApiErrorCode.PARSE_FAILED, retryable = false)
        }
        return update { current ->
            val existingByExternalId = current.courses.values.associateBy { it.course.source.externalId }
            val updated = current.courses.toMutableMap()
            for (record in records) {
                val existing = existingByExternalId[record.externalId]
                val source = SourceRef(Provider.SCHOOL, record.externalId, record.url, record.remoteUpdatedAt)
                val course = existing?.course?.copy(name = record.name, source = source)
                    ?: Course(
                        id = UUID.randomUUID().toString(),
                        name = record.name,
                        source = source,
                        syncEnabled = true,
                        sync = SyncStatus(SyncState.IDLE, null, null, null),
                    )
                updated[course.id] = StoredCourse(course, existing?.checkpoint)
            }
            val activeIds = if (replaceActiveRoster) ids.toSet() else current.activeCourseExternalIds
            current.copy(courses = updated, activeCourseExternalIds = activeIds) to ApiResult.Success(Unit)
        }
    }

    override suspend fun readCheckpoint(courseId: String): AssignmentCheckpoint? =
        state.value.courses[courseId]?.checkpoint?.let(::AssignmentCheckpoint)

    override suspend fun recordAttempt(
        jobId: String,
        courseId: String,
        expectedCheckpoint: AssignmentCheckpoint?,
        fetched: ApiResult<AssignmentDelta>,
        completedAt: Instant,
    ): ApiResult<Unit> = update {
        it.recordCourseResult(jobId, courseId, expectedCheckpoint, fetched, completedAt) to ApiResult.Success(Unit)
    }

    private fun readSnapshot(): SchoolState {
        if (Files.notExists(path)) return SchoolState()
        val snapshot = try {
            Files.newBufferedReader(path).use { json.fromJson(it, Snapshot::class.java) }
        } catch (invalid: JsonParseException) {
            throw IOException("Invalid school store snapshot", invalid)
        } catch (invalid: DateTimeParseException) {
            throw IOException("Invalid date in school store snapshot", invalid)
        }
        if (snapshot?.version != SNAPSHOT_VERSION) throw IOException("Invalid school store snapshot version")
        return snapshot.state ?: throw IOException("Missing school store snapshot state")
    }

    private fun writeSnapshot(state: SchoolState) {
        Files.createDirectories(path.parent)
        val stagedFile = Files.createTempFile(path.parent, ".school-", ".tmp")
        try {
            FileOutputStream(stagedFile.toFile()).use { stream ->
                val output = stream.bufferedWriter()
                json.toJson(Snapshot(SNAPSHOT_VERSION, state), output)
                output.flush()
                stream.fd.sync()
            }
            Files.move(stagedFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(stagedFile)
        }
    }

    private data class Snapshot(val version: Int, val state: SchoolState?)

    private companion object {
        const val SNAPSHOT_VERSION = 1

        val json = GsonBuilder()
            .registerTypeAdapter(Instant::class.java, temporalAdapter(Instant::parse))
            .registerTypeAdapter(LocalDate::class.java, temporalAdapter(LocalDate::parse))
            .registerTypeAdapter(LocalTime::class.java, temporalAdapter(LocalTime::parse))
            .create()

        fun <T : Any> temporalAdapter(parse: (String) -> T): TypeAdapter<T> =
            object : TypeAdapter<T>() {
                override fun write(output: JsonWriter, value: T) {
                    output.value(value.toString())
                }

                override fun read(input: JsonReader): T = parse(input.nextString())
            }.nullSafe()
    }
}
