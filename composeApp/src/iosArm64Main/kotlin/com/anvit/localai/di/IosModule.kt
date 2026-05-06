package com.anvit.localai.di

import com.anvit.localai.document.DocumentParser
import com.anvit.localai.document.IngestionForegroundController
import com.anvit.localai.document.IosIngestionForegroundController
import com.anvit.localai.document.IosDocxParser
import com.anvit.localai.document.IosPdfExtractor
import com.anvit.localai.document.IosPdfHierarchicalParser
import com.anvit.localai.document.PdfExtractor
import com.anvit.localai.download.DownloadService
import com.anvit.localai.download.IosDownloadService
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.embedding.IosEmbeddingService
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.inference.IosInferenceService
import org.koin.dsl.module

/**
 * iOS platform Koin module (iosArm64).
 *
 * Provides the iOS-specific implementations of every interface:
 *   InferenceService  → IosInferenceService (LiteRT-LM C API via cinterop)
 *   EmbeddingService  → IosEmbeddingService (stub — v2: MediaPipe Text Embedder)
 *   DownloadService   → IosDownloadService  (using NSURLSession)
 *   PdfExtractor      → IosPdfExtractor     (stub — v2: PDFKit)
 *
 * Load alongside [commonModule] from your Swift App.init:
 *   KoinHelper.doInitKoin()
 */
val iosModule = module {
    single<InferenceService> { IosInferenceService() }
    single<EmbeddingService> { IosEmbeddingService() }
    single<DownloadService>  { IosDownloadService()  }
    single<PdfExtractor>     { IosPdfExtractor()     }
    single<IngestionForegroundController> { IosIngestionForegroundController() }
    single<List<DocumentParser>> { listOf(IosPdfHierarchicalParser(), IosDocxParser()) }
}
