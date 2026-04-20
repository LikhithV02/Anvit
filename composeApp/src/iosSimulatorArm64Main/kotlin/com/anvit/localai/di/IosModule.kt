package com.anvit.localai.di

import com.anvit.localai.document.IosPdfExtractor
import com.anvit.localai.document.PdfExtractor
import com.anvit.localai.download.DownloadService
import com.anvit.localai.download.IosDownloadService
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.embedding.IosEmbeddingService
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.inference.IosInferenceService
import org.koin.dsl.module

/**
 * iOS Simulator Koin module — mirrors the device module but binds the Simulator stubs.
 * InferenceService is a no-op stub (LiteRT-LM device-only in v1).
 */
val iosModule = module {
    single<InferenceService> { IosInferenceService() }
    single<EmbeddingService> { IosEmbeddingService() }
    single<DownloadService>  { IosDownloadService()  }
    single<PdfExtractor>     { IosPdfExtractor()     }
}
