package org.deepmatcha.mad.backend.school.canvas

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult

/** Collects an entire Canvas REST list before a source can apply any of its rows. */
internal class CanvasClient(
    private val credential: suspend () -> String?,
    private val transport: CanvasTransport = UrlConnectionCanvasTransport,
) {
    suspend fun getList(path: String): ApiResult<List<JsonObject>> {
        val requested = parseUri(path)?.takeIf { !it.isAbsolute && it.rawAuthority == null }
            ?.let(CANVAS_ORIGIN::resolve)?.takeIf(::isCanvasApi)
            ?: return ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)
        val supplied = try {
            credential()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ApiResult.Failure(ApiErrorCode.INTERNAL, retryable = true)
        }
        if (supplied.isNullOrBlank()) {
            return ApiResult.Failure(ApiErrorCode.UNAUTHENTICATED, retryable = false, detail = "canvas_credential_missing")
        }
        if (!BEARER_VALUE.matches(supplied)) {
            return ApiResult.Failure(ApiErrorCode.UNAUTHENTICATED, retryable = false, detail = "canvas_credential_invalid")
        }

        val seen = mutableSetOf<URI>()
        val rows = mutableListOf<JsonObject>()
        val resourcePath = requested.rawPath.removeSuffix(".json")
        var page: URI? = requested
        while (page != null) {
            currentCoroutineContext().ensureActive()
            if (!seen.add(page)) return parseFailure()
            val response = try {
                transport.get(page, supplied)
            } catch (_: IOException) {
                return ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true)
            }
            if (response.status != 200) return httpFailure(response.status)

            val parsed = try {
                STRICT_GSON.fromJson(response.body, JsonElement::class.java)
            } catch (_: JsonParseException) {
                return parseFailure()
            }
            if (parsed !is JsonArray) return parseFailure()
            for (entry in parsed) {
                if (entry !is JsonObject) return parseFailure()
                rows += entry
            }

            val next = try {
                nextLink(response.links)
            } catch (_: InvalidLink) {
                return parseFailure()
            }
            page = if (next == null) null else {
                parseUri(next)?.takeIf { isCanvasApi(it) && it.rawPath.removeSuffix(".json") == resourcePath }
                    ?: return parseFailure()
            }
        }
        return ApiResult.Success(rows)
    }

    private fun httpFailure(status: Int): ApiResult.Failure = when (status) {
        401 -> ApiResult.Failure(ApiErrorCode.UNAUTHENTICATED, retryable = false, detail = "canvas_unauthorized")
        403 -> ApiResult.Failure(ApiErrorCode.FORBIDDEN, retryable = false)
        429 -> ApiResult.Failure(ApiErrorCode.RATE_LIMIT, retryable = true)
        else -> ApiResult.Failure(ApiErrorCode.SOURCE_UNAVAILABLE, retryable = status == 408 || status in 500..599)
    }

    private fun parseFailure() = ApiResult.Failure(ApiErrorCode.PARSE_FAILED, retryable = false)
}

internal const val CANVAS_WEB = "https://eclass3.cau.ac.kr"
private val CANVAS_ORIGIN = URI.create(CANVAS_WEB)
private val BEARER_VALUE = Regex("[A-Za-z0-9._~+/-]+=*")
private val AUTH_QUERY_KEYS = setOf("access_token", "api_key", "oauth_token", "authorization", "token")
private val STRICT_GSON = GsonBuilder().setStrictness(Strictness.STRICT).create()

private fun parseUri(value: String): URI? = try {
    URI(value)
} catch (_: URISyntaxException) {
    null
}

private fun isCanvasApi(uri: URI): Boolean {
    val path = uri.rawPath ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(CANVAS_ORIGIN.host, ignoreCase = true) &&
        (uri.port == -1 || uri.port == 443) &&
        uri.rawUserInfo == null && uri.rawFragment == null &&
        path.startsWith("/api/v1/") && path == uri.path && path == uri.normalize().rawPath &&
        uri.rawQuery.orEmpty().split('&', ';').none { parameter ->
            URLDecoder.decode(parameter.substringBefore('='), Charsets.UTF_8.name()).lowercase() in AUTH_QUERY_KEYS
        }
}

// Parse commas and semicolons as separators only outside the URL and quoted parameters.
private const val LINK_PARAMETER_PATTERN = """\s*;\s*([\w.-]+)(?:\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^\s;,"]+)))?"""
private val LINK_PARAMETER = Regex(LINK_PARAMETER_PATTERN)
private val LINK = Regex("""\s*<([^<>]+)>((?:$LINK_PARAMETER_PATTERN)*)\s*(?:,|$)""")
private val RELATION_SEPARATOR = Regex("\\s+")

private fun nextLink(headers: List<String>): String? {
    var next: String? = null
    for (header in headers) {
        val value = header.trim()
        var offset = 0
        while (offset < value.length) {
            val link = LINK.matchAt(value, offset) ?: throw InvalidLink()
            offset = link.range.last + 1
            val relation = LINK_PARAMETER.findAll(link.groupValues[2])
                .filter { it.groupValues[1].equals("rel", ignoreCase = true) }
                .singleOrNull() ?: throw InvalidLink()
            val kinds = relation.groupValues[2].ifEmpty { relation.groupValues[3] }
            if (kinds.isBlank()) throw InvalidLink()
            if (kinds.split(RELATION_SEPARATOR).any { it.equals("next", ignoreCase = true) }) {
                if (next != null) throw InvalidLink()
                next = link.groupValues[1]
            }
        }
    }
    return next
}

private class InvalidLink : Exception()
