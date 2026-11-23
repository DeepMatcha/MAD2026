package org.deepmatcha.mad.backend.google

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.deepmatcha.mad.backend.school.GoogleExport
import org.deepmatcha.mad.contract.*
import org.junit.Assert.*
import org.junit.Test

class GoogleCalendarApiTest {
    @Test fun calendarPagesAndKstAndExclusiveAllDayEnd() = runBlocking {
        val urls = mutableListOf<URL>()
        val answers = ArrayDeque(listOf(
            """{"items":[{"id":"A","summary":"A","accessRole":"reader"}],"nextPageToken":"a+b"}""",
            """{"items":[{"id":"B","summary":"B","primary":true,"accessRole":"owner"}]}""",
            """{"items":[{"id":"1","start":{"date":"2026-11-02"},"end":{"date":"2026-11-04"}},
              {"id":"2","start":{"dateTime":"2026-11-02T23:00:00Z"},"end":{"dateTime":"2026-11-03T00:00:00Z"}}]}"""))
        val api = GoogleCalendarApi({ "synthetic" }, {}) {
            urls += it
            Reply(it, 200, answers.removeFirst())
        }
        val list = api.calendars()
        assertFalse(list.first().writable)
        assertTrue(list.last().primary)
        assertTrue(urls[1].query.contains("pageToken=a%2Bb"))
        val range = DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))
        val events = api.events("A/B", range)
        assertEquals(LocalDate.parse("2026-11-03"), events.first().endAt!!.date)
        assertNull(events.first().startAt.time)
        assertEquals(KstMoment(LocalDate.parse("2026-11-03"), LocalTime.of(8, 0)), events.last().startAt)
        assertTrue(urls.last().path.contains("A%2FB"))
    }

    @Test fun retryChecksOwnershipBeforeUpdatingOrDeleting() = runBlocking {
        val date = LocalDate.parse("2026-11-02")
        val item = CalendarItem("local", Origin.USER, CalendarKind.EVENT, "New title", null,
            KstMoment(date, LocalTime.NOON), null, null, null, null, null, null, null, false, null)
        val entry = GoogleExport("cal", "abc123", item)
        val requests = mutableListOf<Reply>()
        val responses = ArrayDeque(listOf(409 to "", 200 to """{"extendedProperties":{"private":{"madLocalId":"local"}}}""",
            200 to "{}", 200 to """{"extendedProperties":{"private":{"madLocalId":"foreign"}}}"""))
        val api = GoogleCalendarApi({ "synthetic" }, {}) { url ->
            val (status, body) = responses.removeFirst()
            Reply(url, status, body).also(requests::add)
        }
        api.send(entry)
        assertEquals(listOf("POST", "GET", "POST"), requests.map { it.requestMethod })
        assertEquals("PATCH", requests.last().getRequestProperty("X-HTTP-Method-Override"))
        val body = JsonParser.parseString(requests.first().body.toString("UTF-8")).asJsonObject
        assertEquals(entry.eventId, body.get("id").asString)
        assertEquals("2026-11-02T12:00:00+09:00", body.getAsJsonObject("start").get("dateTime").asString)
        assertTrue(body.get("endTimeUnspecified").asBoolean)
        try {
            api.send(entry.copy(deleting = true))
            fail("foreign event was accepted")
        } catch (failure: GoogleFailure) { assertEquals(ApiErrorCode.CONFLICT, failure.code) }
        assertFalse(requests.any { it.requestMethod == "DELETE" })
    }

    private class Reply(url: URL, val status: Int, val response: String) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun getOutputStream() = body
        override fun getInputStream() = response.byteInputStream()
        override fun getResponseCode() = status
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
