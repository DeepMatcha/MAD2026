package org.deepmatcha.mad

import android.app.Application
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult
import org.deepmatcha.mad.ondevice.litert.GemmaIntegrityException
import org.deepmatcha.mad.ondevice.litert.GemmaModel
import org.deepmatcha.mad.ondevice.litert.GemmaModelStore
import org.deepmatcha.mad.ondevice.litert.GemmaStorageException
import org.deepmatcha.mad.ondevice.litert.OnDeviceSummarizer

internal data class SummaryUiState(
    val downloading: Boolean = false,
    val generating: Boolean = false,
    val feedback: String = "",
    val answer: String = "",
)

class SummaryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = GemmaModelStore.get(app)
    private val summarizer = OnDeviceSummarizer(app)
    private val mutableUi = MutableStateFlow(SummaryUiState())
    internal val ui = mutableUi.asStateFlow()
    val models = store.state
    var choice: GemmaModel? = models.value.selected
    var draft = ""
        private set
    private var downloadJob: Job? = null

    fun edit(text: String) {
        if (draft == text) return
        draft = text
        mutableUi.value = mutableUi.value.copy(answer = "", feedback = "")
    }

    fun selectModel() {
        val model = choice ?: return
        if (mutableUi.value.downloading || mutableUi.value.generating) return
        mutableUi.value = SummaryUiState(downloading = true)
        downloadJob = scope.launch {
            var feedback = ""
            try {
                store.select(model)
            } catch (cancelled: CancellationException) {
                feedback = app.getString(R.string.download_cancelled)
                throw cancelled
            } catch (error: GemmaStorageException) {
                feedback = app.getString(R.string.storage_error,
                    Formatter.formatFileSize(app, error.requiredBytes),
                    Formatter.formatFileSize(app, error.availableBytes))
            } catch (_: GemmaIntegrityException) {
                feedback = app.getString(R.string.integrity_error)
            } catch (_: IOException) {
                feedback = app.getString(R.string.download_failed)
            } finally {
                mutableUi.value = SummaryUiState(feedback = feedback)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun summarize() {
        val model = models.value.selected ?: return
        if (choice != model || draft.isBlank() || mutableUi.value.downloading || mutableUi.value.generating) return
        val input = draft
        mutableUi.value = SummaryUiState(generating = true, feedback = app.getString(R.string.generating))
        scope.launch {
            mutableUi.value = when (val result = summarizer.summarize(input)) {
                is ApiResult.Success -> SummaryUiState(answer = result.data)
                is ApiResult.Failure -> {
                    val message = when (result.code) {
                        ApiErrorCode.INVALID_ARGUMENT -> R.string.input_error
                        ApiErrorCode.NOT_FOUND -> R.string.model_missing
                        ApiErrorCode.UNSUPPORTED -> R.string.model_unsupported
                        else -> R.string.inference_failed
                    }
                    SummaryUiState(feedback = app.getString(message))
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
