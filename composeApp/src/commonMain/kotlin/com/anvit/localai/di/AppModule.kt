package com.anvit.localai.di

import com.anvit.localai.agentic.AgenticRagOrchestrator
import com.anvit.localai.data.db.AnvitDatabase
import com.anvit.localai.data.db.getDatabaseBuilder
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.data.preferences.createDataStore
import com.anvit.localai.data.reporting.ReportingService
import com.anvit.localai.document.DocumentIngestionService
import com.anvit.localai.retrieval.HybridRetriever
import com.anvit.localai.ui.viewmodels.ChatViewModel
import com.anvit.localai.ui.viewmodels.CollectionsViewModel
import com.anvit.localai.ui.viewmodels.DocumentsViewModel
import com.anvit.localai.ui.viewmodels.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Common Koin module — platform modules add InferenceService, EmbeddingService, DownloadService. */
val commonModule = module {
    // Preferences
    single { AnvitPreferences(createDataStore()) }

    // Database
    single {
        getDatabaseBuilder()
            .also { /* migrations applied inside getDatabaseBuilder() */ }
            .build()
    }
    single { get<AnvitDatabase>().documentDao() }
    single { get<AnvitDatabase>().chatDao() }
    single { get<AnvitDatabase>().chatSessionDao() }
    single { get<AnvitDatabase>().collectionDao() }

    // Retrieval
    single { HybridRetriever(get(), get()) }

    // Agentic orchestrator
    single { AgenticRagOrchestrator(get(), get(), get()) }

    // Document ingestion
    single { DocumentIngestionService(get(), get(), get()) }

    // Reporting
    single { ReportingService() }

    // ViewModels
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { DocumentsViewModel(get(), get(), get(), get(), get()) }
    viewModel { CollectionsViewModel(get(), get(), get()) }
}
