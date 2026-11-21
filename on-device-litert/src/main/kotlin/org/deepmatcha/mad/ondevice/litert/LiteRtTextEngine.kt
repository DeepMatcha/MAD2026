package org.deepmatcha.mad.ondevice.litert

import android.os.Build
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.backend.ai.OnDeviceTextEngine
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult

/** CPU inference using a local .litertlm model supplied by the app. */
class LiteRtTextEngine(
    override val modelId: String,
    private val modelFile: File,
    private val cacheDirectory: File? = null,
) : OnDeviceTextEngine {
    override suspend fun generate(prompt: String): ApiResult<String> = inferenceLock.withLock {
        withContext(Dispatchers.Default) {
            if (prompt.isBlank() || modelId.isBlank() || !modelFile.extension.equals("litertlm", ignoreCase = true)) {
                return@withContext failure(ApiErrorCode.INVALID_ARGUMENT)
            }
            if (!Process.is64Bit() || Build.SUPPORTED_64_BIT_ABIS.none { it in supportedAbis }) {
                return@withContext failure(ApiErrorCode.UNSUPPORTED, "on_device_abi")
            }
            if (!modelFile.isFile || !modelFile.canRead()) {
                return@withContext failure(ApiErrorCode.NOT_FOUND, "on_device_model_file")
            }
            if (cacheDirectory != null && (!cacheDirectory.isDirectory || !cacheDirectory.canWrite())) {
                return@withContext failure(ApiErrorCode.INVALID_ARGUMENT, "on_device_cache_directory")
            }

            try {
                Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDirectory?.absolutePath,
                )
                val text = Engine(config).use {
                    it.initialize()
                    it.createConversation(
                        ConversationConfig(automaticToolCalling = false, maxOutputToken = 1024),
                    ).use { conversation ->
                        conversation.sendMessage(prompt).contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { content -> content.text }
                    }
                }
                if (text.isBlank()) failure(ApiErrorCode.PARSE_FAILED) else ApiResult.Success(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                failure(ApiErrorCode.UNSUPPORTED, "on_device_runtime")
            } catch (_: OutOfMemoryError) {
                failure(ApiErrorCode.SOURCE_UNAVAILABLE, "on_device_memory")
            } catch (_: Exception) {
                failure(ApiErrorCode.SOURCE_UNAVAILABLE, "on_device_inference")
            }
        }
    }

    private fun failure(code: ApiErrorCode, detail: String? = null) =
        ApiResult.Failure(code, retryable = false, detail = detail)

    private companion object {
        val supportedAbis = setOf("arm64-v8a", "x86_64")
        val inferenceLock = Mutex()
    }
}
