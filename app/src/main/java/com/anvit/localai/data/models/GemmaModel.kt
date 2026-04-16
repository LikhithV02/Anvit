package com.anvit.localai.data.models

data class GemmaModel(
    val id: String,
    val displayName: String,
    val fileName: String,           // local filename saved under filesDir/models/
    val downloadUrl: String,        // HuggingFace direct download URL
    val sizeBytes: Long,            // approx download size
    val sizeLabel: String,
    val ramRequired: String,
    val isDefault: Boolean = false,
    val contextWindowSize: Int = 32768,
    val supportsVision: Boolean = false,  // true = model accepts image input
    val supportsAudio: Boolean = false    // true = model accepts audio input
)

data class EmbeddingModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val description: String,
    val isRecommended: Boolean = false
)

object GemmaModels {
    val E2B = GemmaModel(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B",
        // Actual filename from HuggingFace litert-community
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        sizeBytes = 2_772_000_000L, // ~2.58 GB
        sizeLabel = "2B",
        ramRequired = "~1.7 GB RAM (CPU)",
        isDefault = true,
        contextWindowSize = 32768,
        supportsVision = true,  // Gemma 4 is multimodal
        supportsAudio = true
    )
    val E4B = GemmaModel(
        id = "gemma4-e4b",
        displayName = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        sizeBytes = 3_922_000_000L, // ~3.65 GB
        sizeLabel = "4B",
        ramRequired = "~3.2 GB RAM (CPU)",
        isDefault = false,
        contextWindowSize = 32768,
        supportsVision = true,  // Gemma 4 is multimodal
        supportsAudio = true
    )
    val all = listOf(E2B, E4B)
}

object EmbeddingModels {
    // Recommended: EmbeddingGemma 300M seq2048 (~196 MB)
    val EMBEDDING_GEMMA_2048 = EmbeddingModelInfo(
        id = "embeddinggemma-2048",
        displayName = "EmbeddingGemma 300M (seq2048)",
        fileName = "embeddinggemma-300M_seq2048_mixed-precision.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq2048_mixed-precision.tflite?download=true",
        sizeBytes = 195_983_360L, // ~196 MB
        description = "High-quality embeddings, 2048 token context. Recommended.",
        isRecommended = true
    )
    // Lighter alternative: Gecko 512-dim (~115 MB)
    val GECKO_512 = EmbeddingModelInfo(
        id = "gecko-512",
        displayName = "Gecko 110M (512-dim)",
        fileName = "Gecko_512_quant.tflite",
        downloadUrl = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/Gecko_512_quant.tflite?download=true",
        sizeBytes = 120_432_640L, // ~115 MB
        description = "Faster, lighter alternative. 512-dim quantized.",
        isRecommended = false
    )
    val all = listOf(EMBEDDING_GEMMA_2048, GECKO_512)
}
