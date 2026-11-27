package org.deepmatcha.mad.ondevice.litert

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GemmaDownload(val model: GemmaModel, val bytes: Long)

data class GemmaModelState(
    val selected: GemmaModel?,
    val installed: Set<GemmaModel>,
    val download: GemmaDownload? = null,
)

class GemmaStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Insufficient storage for model download")

class GemmaIntegrityException : IOException("Model download failed integrity verification")

/** A process-wide store of verified, revision-pinned model packages. */
class GemmaModelStore internal constructor(
    private val directory: File,
    private val artifactFor: (GemmaModel) -> ModelArtifact = { it.artifact },
) {
    private val selectionFile = File(directory, "selected")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<GemmaModelState> = mutableState.asStateFlow()

    private fun modelFile(model: GemmaModel) =
        File(directory, "${model.name}-${artifactFor(model).sha256}.litertlm")

    private fun installed(model: GemmaModel): Boolean {
        val file = modelFile(model)
        return file.isFile && file.length() == artifactFor(model).sizeBytes
    }

    private fun loadState(): GemmaModelState {
        val models = GemmaModel.entries.filter(::installed).toSet()
        val name = if (selectionFile.isFile) selectionFile.readText() else null
        return GemmaModelState(models.find { it.name == name }, models)
    }

    fun selectedFile(): File? = state.value.selected?.takeIf(::installed)?.let(::modelFile)

    /** Select an installed model offline, or fetch it and activate it after verification. */
    suspend fun select(model: GemmaModel): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            Files.createDirectories(directory.toPath())
            val file = modelFile(model)
            try {
                if (!installed(model)) install(model, file)
                currentCoroutineContext().ensureActive()
                val staged = File(directory, "selected.tmp")
                FileOutputStream(staged).use { out ->
                    out.write(model.name.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                replace(staged, selectionFile)
                file
            } finally {
                mutableState.value = loadState()
            }
        }
    }

    private suspend fun install(model: GemmaModel, destination: File) {
        val artifact = artifactFor(model)
        val partial = File(directory, "${destination.name}.part")
        if (partial.length() > artifact.sizeBytes) Files.delete(partial.toPath())
        val offset = partial.length()
        progress(model, offset)
        if (offset < artifact.sizeBytes) {
            ensureSpace(artifact.sizeBytes - offset)
            transfer(model, artifact, partial, offset)
        }
        verify(artifact, partial)
        currentCoroutineContext().ensureActive()
        replace(partial, destination)
    }

    private fun ensureSpace(required: Long) {
        val available = directory.usableSpace
        if (required > available) throw GemmaStorageException(required, available)
    }

    private fun progress(model: GemmaModel, count: Long) {
        mutableState.value = mutableState.value.copy(download = GemmaDownload(model, count))
    }

    private suspend fun transfer(model: GemmaModel, artifact: ModelArtifact, partial: File, offset: Long) {
        val http = URL(artifact.url).openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 15_000
            http.readTimeout = 30_000
            http.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
            val start = when (val status = http.responseCode) {
                HttpURLConnection.HTTP_OK -> 0L
                HttpURLConnection.HTTP_PARTIAL -> {
                    val expected = "bytes $offset-${artifact.sizeBytes - 1}/${artifact.sizeBytes}"
                    if (http.getHeaderField("Content-Range") != expected) throw GemmaIntegrityException()
                    offset
                }
                else -> throw IOException("Model download returned HTTP $status")
            }
            val length = http.contentLengthLong
            if (length >= 0 && length != artifact.sizeBytes - start) throw GemmaIntegrityException()
            http.inputStream.use { input ->
                FileOutputStream(partial, start > 0).use { out ->
                    ensureSpace(artifact.sizeBytes - start)
                    val buffer = ByteArray(256 * 1024)
                    var count = start
                    var lastProgress = start
                    progress(model, count)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        if (bytes > artifact.sizeBytes - count) throw GemmaIntegrityException()
                        out.write(buffer, 0, bytes)
                        count += bytes
                        if (count - lastProgress >= 1024 * 1024) {
                            progress(model, count)
                            lastProgress = count
                        }
                    }
                    if (count != artifact.sizeBytes) throw IOException("Incomplete model download")
                    out.fd.sync()
                    progress(model, count)
                }
            }
        } finally {
            http.disconnect()
        }
    }

    private suspend fun verify(artifact: ModelArtifact, partial: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = input.read(buffer)
                if (bytes < 0) break
                digest.update(buffer, 0, bytes)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != artifact.sha256) {
            Files.delete(partial.toPath())
            throw GemmaIntegrityException()
        }
    }

    private fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private var instance: GemmaModelStore? = null

        @Synchronized
        fun get(context: Context): GemmaModelStore = instance ?: GemmaModelStore(
            File(context.applicationContext.noBackupFilesDir, "gemma-models"),
        ).also { instance = it }
    }
}
