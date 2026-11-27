package org.deepmatcha.mad.backend.ai

import org.deepmatcha.mad.contract.ApiResult

/**
 * Runs text inference on the Android device. The caller owns the runtime and its model files.
 * An engine does not send prompts to a remote service.
 */
interface OnDeviceTextEngine {
    val modelId: String

    /** Returns generated text or a structured failure without logging the input. */
    suspend fun generate(prompt: String): ApiResult<String>
}
