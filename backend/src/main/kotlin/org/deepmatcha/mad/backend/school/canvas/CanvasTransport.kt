package org.deepmatcha.mad.backend.school.canvas

import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface CanvasTransport {
    suspend fun get(url: URI, credential: String): CanvasResponse
}

internal data class CanvasResponse(
    val status: Int,
    val body: String,
    val links: List<String> = emptyList(),
)

internal object UrlConnectionCanvasTransport : CanvasTransport {
    override suspend fun get(url: URI, credential: String): CanvasResponse = withContext(Dispatchers.IO) {
        val connection = url.toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer " + credential)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) {
                CanvasResponse(status, body = "")
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val links = connection.headerFields.entries
                    .filter { it.key.equals("Link", ignoreCase = true) }
                    .flatMap { it.value }
                CanvasResponse(status, body, links)
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MILLIS = 20_000
}
