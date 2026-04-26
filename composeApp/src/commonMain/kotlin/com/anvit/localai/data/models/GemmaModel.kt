package com.anvit.localai.data.models

/** Which runtime platform a model targets. */
enum class ModelPlatform { ANDROID, IOS }

data class GemmaModel(
    val id: String,
    val displayName: String,
    val fileName: String,         // file name (Android) or directory name (iOS MLX)
    val downloadUrl: String,      // direct URL (Android) or "hf://<repo_id>" (iOS MLX)
    val sizeBytes: Long,
    val sizeLabel: String,
    val ramRequired: String,
    val platform: ModelPlatform,
    val isDefault: Boolean = false,
    val contextWindowSize: Int = 32768,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false
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
        sizeBytes        = 2_772_000_000L,
        sizeLabel        = "2B",
        ramRequired      = "~1.7 GB RAM (CPU)",
        platform         = ModelPlatform.ANDROID,
        isDefault        = true,
        contextWindowSize = 32768,
        supportsVision   = true,
        supportsAudio    = true
    )

    val E4B = GemmaModel(
        id               = "gemma4-e4b",
        displayName      = "Gemma 4 E4B",
        fileName         = "gemma-4-E4B-it.litertlm",
        downloadUrl      = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        sizeBytes        = 3_922_000_000L,
        sizeLabel        = "4B",
        ramRequired      = "~3.2 GB RAM (CPU)",
        platform         = ModelPlatform.ANDROID,
        isDefault        = false,
        contextWindowSize = 32768,
        supportsVision   = true,
        supportsAudio    = true
    )

    // ── iOS models (MLX format, downloaded from HuggingFace Hub) ──────────────
    // downloadUrl prefix "hf://" signals IosDownloadService to use Hub multi-file download.
    // fileName is the local directory name (= repo name without the org prefix).

    val MLX_E2B = GemmaModel(
        id               = "gemma4-mlx-e2b",
        displayName      = "Gemma 4 2B (MLX)",
        fileName         = "gemma-4-e2b-it-4bit",
        downloadUrl      = "hf://mlx-community/gemma-4-e2b-it-4bit",
        sizeBytes        = 3_613_529_644L,
        sizeLabel        = "2B · 4-bit",
        ramRequired      = "~4 GB (Metal GPU)",
        platform         = ModelPlatform.IOS,
        isDefault        = true,
        contextWindowSize = 32768,
        supportsVision   = true,
        supportsAudio    = false
    )

    val MLX_E4B = GemmaModel(
        id               = "gemma4-mlx-e4b",
        displayName      = "Gemma 4 4B (MLX)",
        fileName         = "gemma-4-e4b-it-4bit",
        downloadUrl      = "hf://mlx-community/gemma-4-e4b-it-4bit",
        sizeBytes        = 5_249_810_583L,
        sizeLabel        = "4B · 4-bit",
        ramRequired      = "~6 GB (Metal GPU)",
        platform         = ModelPlatform.IOS,
        isDefault        = false,
        contextWindowSize = 32768,
        supportsVision   = true,
        supportsAudio    = false
    )

    val all = listOf(E2B, E4B, MLX_E2B, MLX_E4B)

    fun forPlatform(ios: Boolean): List<GemmaModel> =
        all.filter { it.platform == if (ios) ModelPlatform.IOS else ModelPlatform.ANDROID }

    fun defaultForPlatform(ios: Boolean): GemmaModel =
        forPlatform(ios).firstOrNull { it.isDefault } ?: forPlatform(ios).first()
}

object EmbeddingModels {
    val EMBEDDING_GEMMA_2048 = EmbeddingModelInfo(
        id           = "embeddinggemma-2048",
        displayName  = "EmbeddingGemma 300M (seq2048)",
        fileName     = "embeddinggemma-300M_seq2048_mixed-precision.tflite",
        downloadUrl  = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq2048_mixed-precision.tflite?download=true",
        sizeBytes    = 195_983_360L,
        description  = "High-quality embeddings, 2048 token context. Recommended.",
        isRecommended = true
    )
    val GECKO_512 = EmbeddingModelInfo(
        id          = "gecko-512",
        displayName = "Gecko 110M (512-dim)",
        fileName    = "Gecko_512_quant.tflite",
        downloadUrl = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/Gecko_512_quant.tflite?download=true",
        sizeBytes   = 120_432_640L,
        description = "Faster, lighter alternative. 512-dim quantized.",
        isRecommended = false
    )
    val all = listOf(EMBEDDING_GEMMA_2048, GECKO_512)
}
