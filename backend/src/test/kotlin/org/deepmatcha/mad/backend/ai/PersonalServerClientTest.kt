package org.deepmatcha.mad.backend.ai

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalServerClientTest {
    @Test
    fun `configuration normalizes v1 and restricts unencrypted traffic to keyless local hosts`() {
        val client = PersonalServerClient()
        assertEquals(
            "https://ai.example.test/proxy/v1",
            client.validate(" https://ai.example.test/proxy/v1/ ", "", "").success().baseUrl,
        )
        for (server in listOf("127.0.0.1", "[::1]", "192.168.10.2", "[fd12::1]", "localhost")) {
            assertEquals("http://$server:1234/v1", client.validate("http://$server:1234", "", null).success().baseUrl)
        }
        val invalid = listOf(
            "http://192.168.1.1" to "sample-key",
            "http://192.169.1.1" to "",
            "http://[fc::1]" to "",
            "http://host.example.test" to "",
            "https://user@host.example.test" to "",
            "https://host.example.test/?trace=1" to "",
            "https://host.example.test/proxy/%2e%2e" to "",
            "https://host.example.test" to "invalid\nkey",
        )
        for ((url, key) in invalid) {
            assertEquals(ApiErrorCode.INVALID_ARGUMENT, (client.validate(url, key, null) as ApiResult.Failure).code)
        }
    }

    @Test
    fun `discovers first advertised model and sends chat completion to the configured origin`() = runBlocking {
        val visited = mutableListOf<String>()
        val client = PersonalServerClient { uri, credential, body ->
            visited += uri.toString()
            assertEquals("sample-key", credential)
            if (body == null) {
                response(200, """{"data":[{"id":"sample-slm"},{"id":"sample-slm-2"}]}""")
            } else {
                val request = JsonParser.parseString(body).asJsonObject
                assertEquals("sample-slm", request.get("model").asString)
                assertEquals("sample prompt", request.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
                assertFalse(request.get("stream").asBoolean)
                response(200, """{"choices":[{"finish_reason":"stop","message":{"content":"sample result"}}]}""")
            }
        }
        val config = client.validate("https://ai.example.test/personal", "sample-key", null).success()
        assertEquals("sample result", client.generate(config, "sample prompt").success())
        assertEquals(
            listOf("https://ai.example.test/personal/v1/models", "https://ai.example.test/personal/v1/chat/completions"),
            visited,
        )
    }

    @Test
    fun `connection test distinguishes unsupported models and authentication failure`() = runBlocking {
        var status = 200
        val client = PersonalServerClient { _, _, _ -> response(status, """{"data":[{"id":"present"}]}""") }
        val valid = client.validate("https://ai.example.test", "", "present").success()
        assertTrue(client.test(valid).success().connected)
        val missing = client.validate("https://ai.example.test", "", "missing").success()
        assertEquals(ApiErrorCode.NOT_FOUND, client.test(missing).success().errorCode)
        status = 401
        assertEquals(ApiErrorCode.UNAUTHENTICATED, client.test(valid).success().errorCode)
        status = 429
        val limited = client.generate(valid, "sample prompt") as ApiResult.Failure
        assertEquals(ApiErrorCode.RATE_LIMIT, limited.code)
        assertTrue(limited.retryable)
    }

    @Test
    fun `rejects incomplete successful responses and respects real HTTP redirect boundary`() = runBlocking {
        val incomplete = PersonalServerClient { _, _, _ ->
            response(200, """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""")
        }
        val config = incomplete.validate("https://ai.example.test", "", "present").success()
        assertEquals(ApiErrorCode.PARSE_FAILED, (incomplete.generate(config, "sample") as ApiResult.Failure).code)

        val redirected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            exchange.use {
                assertNull(exchange.requestHeaders.getFirst("Authorization"))
                exchange.responseHeaders.set("Location", "/other")
                exchange.sendResponseHeaders(302, -1)
            }
        }
        server.createContext("/other") { exchange ->
            exchange.use {
                redirected.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
            }
        }
        server.start()
        try {
            val client = PersonalServerClient()
            val localhost = client.validate("http://127.0.0.1:${server.address.port}", "", null).success()
            assertEquals(ApiErrorCode.SOURCE_UNAVAILABLE, client.test(localhost).success().errorCode)
            assertEquals(0, redirected.get())
        } finally {
            server.stop(0)
        }
    }

    private fun response(status: Int, body: String) = ApiResult.Success(PersonalServerResponse(status, body))

    private fun <T> ApiResult<T>.success(): T = (this as ApiResult.Success<T>).data
}
