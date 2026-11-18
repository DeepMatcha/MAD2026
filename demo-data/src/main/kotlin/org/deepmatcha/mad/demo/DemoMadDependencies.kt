package org.deepmatcha.mad.demo

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.MadDependencies
import org.deepmatcha.mad.contract.PatchField

/** Sample data for previews and development. Each instance has its own in-memory state. */
object DemoMadDependencies {
    fun create(): MadDependencies {
        val fixtures = DemoFixtures(LocalDate.now(ZoneId.of("Asia/Seoul")))
        val local = DemoLocalData(fixtures)
        val preferences = DemoSettings()
        val remote = DemoIntegrations(fixtures, local, preferences)
        return object : MadDependencies {
            override val school = local
            override val calendar = DemoCalendar(fixtures, local, preferences)
            override val timetable = local
            override val notes = local
            override val integrations = remote
            override val sync = remote
            override val analysis = remote
            override val googleCalendar = remote
            override val settings = preferences
        }
    }
}

internal fun <T> T?.asResult(): ApiResult<T> =
    if (this == null) ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)
    else ApiResult.Success(this)

internal fun unsupported() = ApiResult.Failure(ApiErrorCode.UNSUPPORTED, retryable = false)

internal fun invalidArgument() = ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)

internal fun newId(): String = UUID.randomUUID().toString()

internal fun <T> PatchField<T>.or(current: T): T = when (this) {
    PatchField.Unset -> current
    is PatchField.Set -> value
}
