package org.deepmatcha.mad.backend.school

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.Assignment
import org.deepmatcha.mad.contract.Provider
import org.deepmatcha.mad.contract.Resource
import org.deepmatcha.mad.contract.ResourceKind
import org.deepmatcha.mad.contract.SchoolRepository
import org.deepmatcha.mad.contract.SourceRef

internal class LocalSchoolRepository(private val store: SchoolStore) : SchoolRepository {
    override fun observeCourses() = store.state
        .map { state -> state.courses.values.map { it.course } }
        .distinctUntilChanged()

    override suspend fun getCourse(courseId: String) = store.state.value.courses[courseId]?.course.orNotFound()

    override suspend fun setCourseSyncEnabled(courseId: String, enabled: Boolean): ApiResult<Unit> =
        store.update { state ->
            val course = state.courses[courseId] ?: return@update state to notFound()
            val updated = course.copy(course = course.course.copy(syncEnabled = enabled))
            state.copy(courses = state.courses + (courseId to updated)) to ApiResult.Success(Unit)
        }

    override fun observeAssignments(courseId: String?, includeCompleted: Boolean, includeHidden: Boolean) =
        store.state.map { state ->
            state.assignments.values.asSequence().map { it.assignment() }.filter {
                (courseId == null || it.courseId == courseId) &&
                    (includeCompleted || !it.completed) &&
                    (includeHidden || !it.hidden)
            }.toList()
        }.distinctUntilChanged()

    override suspend fun getAssignment(assignmentId: String) =
        store.state.value.assignments[assignmentId]?.assignment().orNotFound()

    override suspend fun setUserCompleted(assignmentId: String, completed: Boolean): ApiResult<Assignment> =
        store.update { state ->
            val assignment = state.assignments[assignmentId] ?: return@update state to notFound()
            val updated = assignment.copy(overrides = assignment.overrides.copy(userCompleted = completed))
            state.copy(assignments = state.assignments + (assignmentId to updated)) to
                ApiResult.Success(updated.assignment())
        }

    override suspend fun setSchoolItemHidden(itemId: String, hidden: Boolean): ApiResult<Unit> =
        store.update { state ->
            val assignment = state.assignments[itemId] ?: return@update state to notFound()
            val updated = assignment.copy(overrides = assignment.overrides.copy(hidden = hidden))
            state.copy(assignments = state.assignments + (itemId to updated)) to ApiResult.Success(Unit)
        }

    override fun observeResources(courseId: String, kinds: Set<ResourceKind>?) =
        store.state.map { state ->
            if (kinds != null && ResourceKind.ASSIGNMENT !in kinds) emptyList()
            else state.assignments.values.filter { it.courseId == courseId && !it.overrides.hidden }.map { it.resource() }
        }.distinctUntilChanged()

    override suspend fun getResource(resourceId: String) =
        store.state.value.assignments[resourceId]?.resource().orNotFound()

    override suspend fun getSource(itemId: String): ApiResult<SourceRef?> {
        val state = store.state.value
        return (state.assignments[itemId]?.assignment()?.source ?: state.courses[itemId]?.course?.source)
            .orNotFound()
    }

    private fun StoredAssignment.resource() = Resource(
        id = id,
        courseId = courseId,
        kind = ResourceKind.ASSIGNMENT,
        title = remote.title,
        contentText = remote.description,
        source = SourceRef(Provider.SCHOOL, remote.externalId, remote.url, remote.remoteUpdatedAt),
        fileInfo = null,
    )
}

internal fun notFound() = ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)

internal fun <T : Any> T?.orNotFound(): ApiResult<T> =
    if (this == null) notFound() else ApiResult.Success(this)
