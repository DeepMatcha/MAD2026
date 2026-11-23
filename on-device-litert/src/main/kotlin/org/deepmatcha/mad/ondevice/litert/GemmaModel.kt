package org.deepmatcha.mad.ondevice.litert

/** CPU LiteRT-LM packages with signed INT8 K and V caches embedded in the graph. */
enum class GemmaModel(
    val label: String,
    val sizeBytes: Long,
    revision: String,
    sha256: String,
) {
    E2B("Gemma 4 E2B", 2_588_147_712L, "2e12bfd869b2962984f97a1f2a26807316d534cc",
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
    E4B("Gemma 4 E4B", 3_659_530_240L, "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
        "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0");

    internal val artifact = ModelArtifact(
        "https://huggingface.co/litert-community/gemma-4-${name}-it-litert-lm/resolve/" +
            "$revision/gemma-4-${name}-it.litertlm",
        sizeBytes,
        sha256,
    )
}

internal data class ModelArtifact(val url: String, val sizeBytes: Long, val sha256: String)
