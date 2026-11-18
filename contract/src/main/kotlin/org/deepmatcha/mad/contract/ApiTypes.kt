package org.deepmatcha.mad.contract

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /** Machine-readable failure. The UI supplies any user-facing text. */
    data class Failure(
        val code: ApiErrorCode,
        val retryable: Boolean,
        val detail: String? = null,
    ) : ApiResult<Nothing>
}

enum class ApiErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    UNAUTHENTICATED,
    AUTH_EXPIRED,
    FORBIDDEN,
    UNSUPPORTED,
    NETWORK,
    RATE_LIMIT,
    SOURCE_UNAVAILABLE,
    PARSE_FAILED,
    CONFLICT,
    CANCELLED,
    INTERNAL,
}

/** [Unset] keeps the current value. [Set] writes the supplied value, including null when allowed. */
sealed interface PatchField<out T> {
    data object Unset : PatchField<Nothing>
    data class Set<T>(val value: T) : PatchField<T>
}

/** Calendar date and optional exact time in Asia/Seoul. A null time is date-only. */
data class KstMoment(val date: LocalDate, val time: LocalTime?)

data class DateRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(from <= to) { "DateRange start must be on or before its end" }
    }
}

enum class Provider { SCHOOL, GOOGLE_CALENDAR, AI }

data class SourceRef(
    val provider: Provider,
    val externalId: String?,
    val url: String?,
    val remoteUpdatedAt: Instant?,
)
