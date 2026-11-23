package org.deepmatcha.mad.backend.google

import com.google.gson.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.backend.school.GoogleExport
import org.deepmatcha.mad.backend.school.exportKey
import org.deepmatcha.mad.contract.*

internal interface GoogleRemote {
    suspend fun calendars(): List<GoogleCalendarInfo>
    suspend fun events(calendar: String, range: DateRange): List<CalendarItem>
    suspend fun send(entry: GoogleExport)
}

internal class GoogleFailure(val code: ApiErrorCode, val retry: Boolean = false) : Exception()
internal val seoul: ZoneId = ZoneId.of("Asia/Seoul")

internal class GoogleCalendarApi(
    private val token: suspend () -> String,
    private val invalidate: suspend (String) -> Unit,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : GoogleRemote {
    override suspend fun calendars() = pages("users/me/calendarList", "maxResults=250").map { row ->
        GoogleCalendarInfo(row.required("id"), row.string("summaryOverride") ?: row.required("summary"),
            row.get("primary")?.asBoolean == true, row.string("accessRole") in setOf("writer", "owner"))
    }

    override suspend fun events(calendar: String, range: DateRange): List<CalendarItem> {
        val min = range.from.atStartOfDay(seoul).toInstant().toString()
        val max = range.to.plusDays(1).atStartOfDay(seoul).toInstant().toString()
        return pages("calendars/${encode(calendar)}/events", "singleEvents=true&maxResults=2500&timeMin=${encode(min)}&timeMax=${encode(max)}")
            .filter { it.string("status") != "cancelled" }.map { row ->
                val id = row.required("id")
                val start = moment(row.obj("start") ?: throw GoogleFailure(ApiErrorCode.PARSE_FAILED))
                val end = moment(row.obj("end") ?: throw GoogleFailure(ApiErrorCode.PARSE_FAILED))
                val inclusiveEnd = if (end.time == null) end.copy(date = end.date.minusDays(1)) else end
                CalendarItem("google:" + exportKey(calendar, id), Origin.GOOGLE, CalendarKind.EVENT,
                    row.string("summary").orEmpty(), row.string("description"), start, inclusiveEnd, null, null, null,
                    row.string("location"), null, null, false, SourceRef(Provider.GOOGLE_CALENDAR, id,
                        row.string("htmlLink"), row.string("updated")?.let(Instant::parse)))
            }
    }

    override suspend fun send(entry: GoogleExport) {
        val root = "calendars/${encode(entry.calendarId)}/events"
        val target = "$root/${encode(entry.eventId)}"
        if (entry.deleting) {
            try {
                checkOwner(request("GET", target), entry)
                request("DELETE", target)
            } catch (e: GoogleFailure) {
                if (e.code != ApiErrorCode.NOT_FOUND) throw e
            }
            return
        }
        val item = entry.item
        val body = JsonObject().apply {
            addProperty("id", entry.eventId)
            addProperty("summary", item.title)
            addProperty("description", item.description)
            addProperty("location", item.location)
            add("start", googleTime(item.startAt))
            val start = item.startAt
            val explicitEnd = item.endAt
            val end = if (start.time == null) {
                KstMoment((explicitEnd?.date ?: start.date).plusDays(1), null)
            } else {
                val begin = start.date.atTime(start.time)
                val until = explicitEnd?.let { it.date.atTime(it.time ?: LocalTime.MIDNIGHT) }
                if (until == null || until <= begin) {
                    addProperty("endTimeUnspecified", true)
                    val placeholder = begin.plusMinutes(1)
                    KstMoment(placeholder.toLocalDate(), placeholder.toLocalTime())
                } else KstMoment(until.toLocalDate(), until.toLocalTime())
            }
            add("end", googleTime(end))
            add("extendedProperties", JsonObject().apply {
                add("private", JsonObject().apply { addProperty("madLocalId", item.id) })
            })
        }
        try { request("POST", root, body) } catch (e: GoogleFailure) {
            if (e.code != ApiErrorCode.CONFLICT) throw e
            val existing = request("GET", target)
            checkOwner(existing, entry)
            body.remove("id")
            body.remove("extendedProperties")
            if (!body.has("endTimeUnspecified")) body.addProperty("endTimeUnspecified", false)
            request("PATCH", target, body)
        }
    }

    private fun checkOwner(row: JsonObject, entry: GoogleExport) {
        if (row.obj("extendedProperties")?.obj("private")?.string("madLocalId") != entry.item.id)
            throw GoogleFailure(ApiErrorCode.CONFLICT)
    }

    private suspend fun pages(path: String, query: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var page: String? = null
        do {
            val reply = request("GET", "$path?$query" + (page?.let { "&pageToken=" + encode(it) } ?: ""))
            val items = reply.get("items")
            if (items != null && !items.isJsonArray) throw GoogleFailure(ApiErrorCode.PARSE_FAILED)
            items?.asJsonArray?.forEach {
                if (!it.isJsonObject) throw GoogleFailure(ApiErrorCode.PARSE_FAILED)
                rows += it.asJsonObject
            }
            page = reply.string("nextPageToken")
            if (page != null && !seen.add(page)) throw GoogleFailure(ApiErrorCode.PARSE_FAILED)
        } while (page != null)
        return rows
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val access = token()
        val connection = connect(URL("https://www.googleapis.com/calendar/v3/$path"))
        try {
            // Some Android HttpURLConnection implementations reject PATCH as a request method.
            connection.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            when (connection.responseCode) {
                204 -> JsonObject()
                in 200..299 -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
                401 -> { invalidate(access); throw GoogleFailure(ApiErrorCode.AUTH_EXPIRED) }
                403 -> throw GoogleFailure(ApiErrorCode.FORBIDDEN)
                404, 410 -> throw GoogleFailure(ApiErrorCode.NOT_FOUND)
                409 -> throw GoogleFailure(ApiErrorCode.CONFLICT)
                429 -> throw GoogleFailure(ApiErrorCode.RATE_LIMIT, true)
                in 500..599 -> throw GoogleFailure(ApiErrorCode.SOURCE_UNAVAILABLE, true)
                else -> throw GoogleFailure(ApiErrorCode.INVALID_ARGUMENT)
            }
        } catch (e: IOException) { throw GoogleFailure(ApiErrorCode.NETWORK, true) }
        finally { connection.disconnect() }
    }

    private fun googleTime(value: KstMoment) = JsonObject().apply {
        if (value.time == null) addProperty("date", value.date.toString())
        else addProperty("dateTime", java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            value.date.atTime(value.time).atZone(seoul)))
    }
    private fun moment(obj: JsonObject): KstMoment {
        obj.string("date")?.let { return KstMoment(LocalDate.parse(it), null) }
        val time = OffsetDateTime.parse(obj.required("dateTime")).atZoneSameInstant(seoul)
        return KstMoment(time.toLocalDate(), time.toLocalTime())
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.required(key: String) = string(key) ?: throw GoogleFailure(ApiErrorCode.PARSE_FAILED)
}
