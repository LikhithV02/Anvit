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
 * iOS Simulator Koin module. ARM64 Simulator inference uses the Cactus simulator slice.
 */
val iosModule = module {
    single<InferenceService> { IosInferenceService() }
    single<EmbeddingService> { IosEmbeddingService() }
    single<DownloadService>  { IosDownloadService()  }
    single<PdfExtractor>     { IosPdfExtractor()     }
    single<IngestionForegroundController> { IosIngestionForegroundController() }
    single<List<DocumentParser>> { listOf(IosPdfHierarchicalParser(), IosDocxParser()) }
}
