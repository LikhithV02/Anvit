package com.anvit.localai.di

import com.anvit.localai.document.AndroidPdfExtractor
import com.anvit.localai.document.PdfExtractor
import com.anvit.localai.download.AndroidDownloadService
import com.anvit.localai.download.DownloadService
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.embedding.GeckoEmbeddingService
import com.anvit.localai.inference.GemmaInferenceService
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.inference.RagAgentTools
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android platform Koin module.
 * Provides the Android-specific implementations of every expect interface:
 *   InferenceService  → GemmaInferenceService (LiteRT-LM JVM SDK)
 *   EmbeddingService  → GeckoEmbeddingService (localagents-rag)
 *   DownloadService   → AndroidDownloadService (HttpURLConnection)
 *   PdfExtractor      → AndroidPdfExtractor (iText7)
 *
 * Must be loaded alongside [commonModule] in the Android Koin start call.
 */
val androidModule = module {
    // RAG agent tools — wired into GemmaInferenceService below
    single { RagAgentTools(retriever = get(), context = androidContext()) }

    // LLM inference (Android: LiteRT-LM JVM SDK)
    single<InferenceService> {
        GemmaInferenceService(context = androidContext()).also { svc ->
            svc.setRagTools(get())
        }
    }

    // Embedding (Android: GeckoEmbeddingModel)
    single<EmbeddingService> { GeckoEmbeddingService(context = androidContext()) }

    // Model download
    single<DownloadService> { AndroidDownloadService(context = androidContext()) }

    // PDF extraction (iText7)
    single<PdfExtractor> { AndroidPdfExtractor() }
}
