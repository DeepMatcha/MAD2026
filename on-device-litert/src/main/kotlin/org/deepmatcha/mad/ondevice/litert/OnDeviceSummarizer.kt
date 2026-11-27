package org.deepmatcha.mad.ondevice.litert

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.deepmatcha.mad.contract.ApiErrorCode
import org.deepmatcha.mad.contract.ApiResult

/** Summarizes the supplied material locally with the user's last successfully selected model. */
class OnDeviceSummarizer(context: Context) {
    private val app = context.applicationContext

    suspend fun summarize(text: String): ApiResult<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ApiResult.Failure(ApiErrorCode.INVALID_ARGUMENT, false)
        val file = GemmaModelStore.get(app).selectedFile()
            ?: return@withContext ApiResult.Failure(ApiErrorCode.NOT_FOUND, false, "on_device_model_file")
        LiteRtTextEngine(file.nameWithoutExtension, file, app.cacheDir).summarize(text)
    }
}

// Keep each passage small enough for byte fallback tokenization to fit inside the 2048-token context, including the instructions
// and output allowance. Prefer a whitespace boundary, and never split a Unicode code point.
internal fun summaryPassages(text: String): Sequence<String> = sequence {
    var start = 0
    while (start < text.length) {
        var end = start
        var bytes = 0
        var lastBreak = start
        while (end < text.length) {
            val point = text.codePointAt(end)
            val width = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            if (bytes + width > 1500) break
            bytes += width
            end += Character.charCount(point)
            if (Character.isWhitespace(point)) lastBreak = end
        }
        if (end < text.length && lastBreak > start) end = lastBreak
        val passage = text.substring(start, end).trim()
        if (passage.isNotEmpty()) yield(passage)
        start = end
    }
}
