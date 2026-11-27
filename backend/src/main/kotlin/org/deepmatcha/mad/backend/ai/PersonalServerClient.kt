package org.deepmatcha.mad.backend.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.contract.ConnectionTestResult

internal class PersonalServerConfig(
    val baseUrl: String,
    val modelId: String?,
    val key: String,
) {
    fun endpoint(path: String): URI = URI.create("$baseUrl/$path")
}

internal class PersonalServerClient(
    private val transport: PersonalServerTransport = PersonalServerHttpTransport,
) {
    fun validate(baseUrl: String, apiKey: String, modelId: String?): ApiResult<PersonalServerConfig> {
        val parsed = try {
            URI(baseUrl.trim())
        } catch (_: URISyntaxException) {
            return invalidArgument("invalid_ai_server_url")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        val host = parsed.host ?: return invalidArgument("invalid_ai_server_url")
        val path = parsed.rawPath.orEmpty().trimEnd('/')
        if (scheme !in setOf("http", "https") || parsed.rawUserInfo != null ||
            parsed.rawQuery != null || parsed.rawFragment != null ||
            (parsed.port != -1 && parsed.port !in 1..65535) ||
            parsed.path.orEmpty().split('/').any { it == "." || it == ".." }
        ) {
            return invalidArgument("invalid_ai_server_url")
        }

        val credential = apiKey.trim()
        if (credential.any { it.code !in 33..126 }) return invalidArgument("invalid_ai_server_key")
        if (scheme == "http" && (!isLocalAddress(host) || credential.isNotEmpty())) {
            return invalidArgument("ai_server_requires_https")
        }

        val apiPath = if (path.endsWith("/v1")) path else "$path/v1"
        val normalized = "$scheme://${parsed.rawAuthority}$apiPath"
        return ApiResult.Success(PersonalServerConfig(normalized, modelId?.trim()?.ifEmpty { null }, credential))
    }

    suspend fun test(config: PersonalServerConfig): ApiResult<ConnectionTestResult> =
        ApiResult.Success(
            when (val result = models(config)) {
                is ApiResult.Failure -> ConnectionTestResult(false, emptySet(), result.code)
                is ApiResult.Success -> {
                    val available = if (config.modelId == null) result.data.isNotEmpty() else config.modelId in result.data
                    if (available) ConnectionTestResult(true, setOf("models"), null)
                    else ConnectionTestResult(false, setOf("models"), ApiErrorCode.NOT_FOUND)
                }
            },
        )

    suspend fun generate(config: PersonalServerConfig, prompt: String): ApiResult<String> {
        val model = config.modelId ?: when (val result = models(config)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data.firstOrNull()
                ?: return ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            })
        }
        return when (val result = request(config, "chat/completions", body.toString())) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val choice = result.data.array("choices")?.firstOrNull() as? JsonObject ?: return parseFailure()
                when (choice.text("finish_reason")) {
                    "content_filter" -> return ApiResult.Failure(ApiErrorCode.FORBIDDEN, retryable = false)
                    "length", "tool_calls", "function_call" -> return parseFailure()
                }
                val message = choice.get("message") as? JsonObject ?: return parseFailure()
                val content = message.text("content")?.takeIf { it.isNotBlank() } ?: return parseFailure()
                ApiResult.Success(content)
            }
        }
    }

    private suspend fun models(config: PersonalServerConfig): ApiResult<List<String>> {
        return when (val result = request(config, "models")) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val rows = result.data.array("data") ?: return parseFailure()
                val models = rows.mapNotNull { row -> (row as? JsonObject)?.text("id")?.takeIf { it.isNotBlank() } }
                if (models.size == rows.size()) ApiResult.Success(models) else parseFailure()
            }
        }
    }

    private suspend fun request(
        config: PersonalServerConfig,
        path: String,
        body: String? = null,
    ): ApiResult<JsonObject> {
        val response = when (val result = transport.execute(config.endpoint(path), config.key, body)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        if (response.status !in 200..299) return httpFailure(response.status)
        val decoded = try {
            STRICT_GSON.fromJson(response.body, JsonElement::class.java)
        } catch (_: JsonParseException) {
            return parseFailure()
        }
        return if (decoded is JsonObject) ApiResult.Success(decoded) else parseFailure()
    }
}

internal data class PersonalServerResponse(val status: Int, val body: String)

internal fun interface PersonalServerTransport {
    suspend fun execute(uri: URI, credential: String, body: String?): ApiResult<PersonalServerResponse>
}

private object PersonalServerHttpTransport : PersonalServerTransport {
    override suspend fun execute(uri: URI, credential: String, body: String?) = withContext(Dispatchers.IO) {
        try {
            // A cleartext LAN request must remain on the direct connection to its configured address.
            val url = uri.toURL()
            val connection = (if (uri.scheme == "http") url.openConnection(Proxy.NO_PROXY) else url.openConnection())
                as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 60_000
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Accept", "application/json")
                if (credential.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer " + credential)
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status !in 200..299) return@withContext ApiResult.Success(PersonalServerResponse(status, ""))
                val response = connection.inputStream.use { it.readBounded() } ?: return@withContext parseFailure()
                ApiResult.Success(PersonalServerResponse(status, response))
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true)
        }
    }
}

private val STRICT_GSON = GsonBuilder().setStrictness(Strictness.STRICT).create()
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

private fun InputStream.readBounded(): String? {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) return output.toString(Charsets.UTF_8.name())
        if (count > MAX_RESPONSE_BYTES - output.size()) return null
        output.write(chunk, 0, count)
    }
}

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.text(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.asString

private fun isLocalAddress(host: String): Boolean {
    val address = host.trim('[', ']').lowercase(Locale.ROOT)
    if (address == "localhost" || address == "::1") return true
    if (':' in address) {
        val prefix = address.substringBefore(':').toIntOrNull(16) ?: return false
        return prefix in 0xfc00..0xfdff
    }
    val octets = address.split('.').mapNotNull { part ->
        part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part }
    }
    if (octets.size != 4 || address.count { it == '.' } != 3) return false
    return when (octets[0]) {
        10, 127 -> true
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        else -> false
    }
}

private fun invalidArgument(detail: String) = ApiResult.Failure(
    ApiErrorCode.INVALID_ARGUMENT, retryable = false, detail = detail,
)

private fun parseFailure() = ApiResult.Failure(ApiErrorCode.PARSE_FAILED, retryable = false)

private fun httpFailure(status: Int) = when (status) {
    400, 422 -> ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, retryable = false)
    401 -> ApiResult.Failure(ApiErrorCode.UNAUTHENTICATED, retryable = false)
    403 -> ApiResult.Failure(ApiErrorCode.FORBIDDEN, retryable = false)
    404 -> ApiResult.Failure(ApiErrorCode.NOT_FOUND, retryable = false)
    408 -> ApiResult.Failure(ApiErrorCode.NETWORK, retryable = true)
    429 -> ApiResult.Failure(ApiErrorCode.RATE_LIMIT, retryable = true)
    else -> ApiResult.Failure(ApiErrorCode.SOURCE_UNAVAILABLE, retryable = status in 500..599)
}
