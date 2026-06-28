package com.anvit.localai.data.models

/** Which runtime platform a model targets. */
enum class ModelPlatform { ANDROID, IOS }

data class GemmaModel(
    val id: String,
    val displayName: String,
    val fileName: String,         // file name (Android) or directory name (iOS)
    val downloadUrl: String,      // direct URL (Android) or "hf://<repo_id>" (iOS)
    val sizeBytes: Long,
    val sizeLabel: String,
    val ramRequired: String,
    val platform: ModelPlatform,
    val isDefault: Boolean = false,
    val contextWindowSize: Int = 32768,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val archiveFileName: String? = null
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

    // ── Android models (LiteRT-LM format) ─────────────────────────────────────

    val E2B = GemmaModel(
        id               = "gemma4-e2b",
        displayName      = "Gemma 4 E2B",
        fileName         = "gemma-4-E2B-it.litertlm",
        downloadUrl      = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        sizeBytes        = 2_588_147_712L,
        sizeLabel        = "2B",
        ramRequired      = "~1.7 GB RAM",
        platform         = ModelPlatform.ANDROID,
        isDefault        = true,
        contextWindowSize = 128000,
        supportsVision   = true,
        supportsAudio    = true
    )

    val E4B = GemmaModel(
        id               = "gemma4-e4b",
        displayName      = "Gemma 4 E4B",
        fileName         = "gemma-4-E4B-it.litertlm",
        downloadUrl      = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        sizeBytes        = 3_659_530_240L,
        sizeLabel        = "4B",
        ramRequired      = "~3.2 GB RAM",
        platform         = ModelPlatform.ANDROID,
        isDefault        = false,
        contextWindowSize = 128000,
        supportsVision   = true,
        supportsAudio    = true
    )

    // ── iOS models (Cactus cq4-apple bundle, downloaded from HuggingFace Hub) ─
    // downloadUrl prefix "hf://" signals IosDownloadService to use HuggingFace.
    // archiveFileName is fetched, unzipped into fileName/, then guarded by .complete.

    val CACTUS_E2B = GemmaModel(
        id               = "gemma4-cactus-e2b",
        displayName      = "Gemma 4 E2B (Cactus)",
        fileName         = "gemma-4-e2b-it-cq4-apple",
        downloadUrl      = "hf://Cactus-Compute/gemma-4-E2B-it",
        archiveFileName  = "gemma-4-e2b-it-cq4-apple.zip",
        sizeBytes        = 3_613_529_644L,
        sizeLabel        = "2B · cq4 Apple",
        ramRequired      = "~4 GB RAM",
        platform         = ModelPlatform.IOS,
        isDefault        = true,
        contextWindowSize = 128000,
        supportsVision   = true,
        supportsAudio    = true
    )

    val CACTUS_E4B = GemmaModel(
        id               = "gemma4-cactus-e4b",
        displayName      = "Gemma 4 E4B (Cactus)",
        fileName         = "gemma-4-e4b-it-cq4-apple",
        downloadUrl      = "hf://Cactus-Compute/gemma-4-E4B-it",
        archiveFileName  = "gemma-4-e4b-it-cq4-apple.zip",
        sizeBytes        = 5_249_810_583L,
        sizeLabel        = "4B · cq4 Apple",
        ramRequired      = "~6 GB RAM",
        platform         = ModelPlatform.IOS,
        isDefault        = false,
        contextWindowSize = 128000,
        supportsVision   = true,
        supportsAudio    = true
    )

    val all = listOf(E2B, E4B, CACTUS_E2B, CACTUS_E4B)

    fun forPlatform(ios: Boolean): List<GemmaModel> =
        all.filter { it.platform == if (ios) ModelPlatform.IOS else ModelPlatform.ANDROID }

    fun defaultForPlatform(ios: Boolean): GemmaModel =
        forPlatform(ios).firstOrNull { it.isDefault } ?: forPlatform(ios).first()
}

object EmbeddingModels {
    val EMBEDDING_GEMMA_2048 = EmbeddingModelInfo(
        id           = "embeddinggemma-2048",
        displayName  = "EmbeddingGemma 300M",
        fileName     = "embeddinggemma-300M_seq2048_mixed-precision.tflite",
        downloadUrl  = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq2048_mixed-precision.tflite?download=true",
        sizeBytes    = 195_983_360L,
        description  = "High-quality · 2048 token context",
        isRecommended = true
    )
    val GECKO_512 = EmbeddingModelInfo(
        id          = "gecko-512",
        displayName = "Gecko 110M",
        fileName    = "Gecko_512_quant.tflite",
        downloadUrl = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/Gecko_512_quant.tflite?download=true",
        sizeBytes   = 120_432_640L,
        description = "Faster · 512-dim quantized.",
        isRecommended = false
    )
    val all = listOf(EMBEDDING_GEMMA_2048, GECKO_512)
}
