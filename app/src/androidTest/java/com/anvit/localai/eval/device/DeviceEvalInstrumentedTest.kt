package com.anvit.localai.eval.device

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anvit.localai.AnvitContextHolder
import com.anvit.localai.agentic.AgenticRagOrchestrator
import com.anvit.localai.agentic.PipelineTrace
import com.anvit.localai.agentic.TraceChunk
import com.anvit.localai.data.db.AnvitDatabase
import com.anvit.localai.data.models.GemmaModel
import com.anvit.localai.data.models.GemmaModels
import com.anvit.localai.data.preferences.AnvitPreferences
import com.anvit.localai.document.AndroidPdfExtractor
import com.anvit.localai.document.DocxHierarchicalParser
import com.anvit.localai.document.DocumentIngestionService
import com.anvit.localai.document.IngestionForegroundController
import com.anvit.localai.document.IngestionResult
import com.anvit.localai.document.PdfHierarchicalParser
import com.anvit.localai.embedding.EmbeddingService
import com.anvit.localai.embedding.GeckoEmbeddingService
import com.anvit.localai.eval.dataset.EvalDataset
import com.anvit.localai.inference.GemmaInferenceService
import com.anvit.localai.inference.InferenceService
import com.anvit.localai.inference.RagAgentTools
import com.anvit.localai.retrieval.HybridRetriever
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.datetime.Clock
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceEvalInstrumentedTest {
    @Test
    fun runDeviceLocalEval() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        AnvitContextHolder.appContext = appContext

        val args = InstrumentationRegistry.getArguments()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val datasetAsset = args.getString("anvit.eval.datasetAsset") ?: "dataset.json"
        val runId = args.getString("anvit.eval.runId") ?: "device-${System.currentTimeMillis()}"
        val maxChunks = args.getString("anvit.eval.maxChunks")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        val enableAgenticRag = args.getString("anvit.eval.enableAgenticRag")?.toBooleanStrictOrNull() ?: false
        val enableSelfCritique = args.getString("anvit.eval.enableSelfCritique")?.toBooleanStrictOrNull() ?: false
        val useAgentTools = args.getString("anvit.eval.useAgentTools")?.toBooleanStrictOrNull() ?: false
        val sampleLimit = args.getString("anvit.eval.sampleLimit")?.toIntOrNull()?.takeIf { it > 0 }
        val sampleTimeoutMs = args.getString("anvit.eval.sampleTimeoutMs")?.toLongOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_SAMPLE_TIMEOUT_MS
        val generateAnswers = args.getString("anvit.eval.generateAnswers")?.toBooleanStrictOrNull() ?: false

        val dataset = EvalDataset.json.decodeFromString(
            EvalDataset.serializer(),
            testAssets.open(datasetAsset).bufferedReader().use { it.readText() }
        ).let { original ->
            sampleLimit?.let { original.copy(samples = original.samples.take(it)) } ?: original
        }
        require(dataset.samples.isNotEmpty()) { "Device eval dataset is empty: $datasetAsset" }

        val preferences = runCatching { GlobalContext.get().get<AnvitPreferences>() }
            .getOrElse { error("Koin/AnvitPreferences is not available in the target app process: ${it.message}") }
        val accelerator = args.getString("anvit.eval.accelerator") ?: preferences.accelerator.first()
        val maxOutputTokens = args.getString("anvit.eval.maxOutputTokens")?.toIntOrNull()
            ?: DEFAULT_EVAL_MAX_OUTPUT_TOKENS
        val contextWindow = args.getString("anvit.eval.contextWindow")?.toIntOrNull()
            ?: DEFAULT_EVAL_CONTEXT_WINDOW
        val topK = args.getString("anvit.eval.topK")?.toIntOrNull() ?: preferences.topK.first()
        val temperature = args.getString("anvit.eval.temperature")?.toFloatOrNull() ?: preferences.temperature.first()
        val enableThinking = args.getString("anvit.eval.enableThinking")?.toBooleanStrictOrNull() ?: false

        val modelsDir = File(appContext.filesDir, "models")
        val availableModelFiles = modelsDir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty().sorted()
        val requestedModelId = args.getString("anvit.eval.modelId") ?: preferences.selectedModelId.first()
        val requestedModelFileName = args.getString("anvit.eval.modelFileName")
        val model = resolveDeviceGemmaModel(
            requestedModelId = requestedModelId,
            requestedModelFileName = requestedModelFileName,
            modelsDir = modelsDir
        )
        require(model != null || !generateAnswers) {
            val expected = androidGemmaCandidates().joinToString { it.fileName }
            "No supported Gemma model file found on device.\n" +
                "Models dir: ${modelsDir.absolutePath}\n" +
                "Requested modelId: $requestedModelId\n" +
                "Requested modelFileName: ${requestedModelFileName ?: "(none)"}\n" +
                "Expected one of: $expected\n" +
                "Available files: ${availableModelFiles.ifEmpty { listOf("(none)") }.joinToString()}\n" +
                "Download a Gemma 4 model in Settings before running device eval."
        }

        val db = Room.inMemoryDatabaseBuilder(appContext, AnvitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // Reuse the Koin singleton to avoid initializing a second GeckoEmbeddingModel
            // JNI instance in the same process — doing so causes a SIGSEGV native crash.
            val embedding = runCatching { GlobalContext.get().get<EmbeddingService>() }
                .getOrElse { GeckoEmbeddingService(appContext, preferences) }
            require(embedding.initialize()) {
                "Embedding model is missing or failed to initialize on device. Download EmbeddingGemma or Gecko in Settings before running device eval."
            }

            val retriever = HybridRetriever(db.documentDao(), embedding)

            val evalModel = model?.copy(supportsVision = false, supportsAudio = false)
            // Validate Gemma availability up-front but DEFER actually loading it until after
            // corpus ingestion. Loading Gemma 4 (~2.6GB) before ingestion causes severe memory
            // swapping during embedding generation (chunks slow from ~22s to >120s each).
            val deferredGemmaLoader: (suspend () -> GemmaInferenceService)? = if (generateAnswers) {
                requireNotNull(evalModel) { "Generated-answer device eval requires a downloaded Gemma model." }
                val inferenceService = runCatching { GlobalContext.get().get<InferenceService>() }
                    .getOrElse { error("Koin/InferenceService is not available in the target app process: ${it.message}") }
                val gemma = inferenceService as? GemmaInferenceService
                    ?: error("InferenceService is not a GemmaInferenceService — cannot run generated-answer device eval")
                ({
                    // Reuse the Koin singleton to avoid initializing a second LiteRT Engine instance.
                    // LiteRT-LM does not support concurrent Engine instances in the same process.
                    gemma.setRagTools(RagAgentTools(retriever, appContext))
                    gemma.setGenerationParams(
                        topK = topK,
                        temperature = temperature,
                        enableThinking = enableThinking,
                        maxTokens = maxOutputTokens,
                        contextWindow = contextWindow,
                        accelerator = accelerator
                    )
                    try {
                        gemma.loadModelOrThrow(evalModel)
                    } catch (e: Exception) {
                        error(
                            "Gemma model failed to load on device: ${evalModel.displayName}\n" +
                                "Model file: ${modelsDir.absolutePath}/${evalModel.fileName}\n" +
                                "Cause: ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                    gemma
                })
            } else {
                println("[DeviceEval] generateAnswers=false; skipping Gemma engine load")
                null
            }

            val ingestion = DocumentIngestionService(
                documentDao = db.documentDao(),
                embeddingService = embedding,
                pdfExtractor = AndroidPdfExtractor(),
                foregroundController = IngestionForegroundController.NoOp,
                documentParsers = listOf(PdfHierarchicalParser(appContext), DocxHierarchicalParser())
            )

            val corpusAssets = corpusAssets(args.getString("anvit.eval.corpusAssets"), testAssets.list("").orEmpty().toList())
            require(corpusAssets.isNotEmpty()) {
                "No PDF or DOCX corpus assets found. Add files to androidTest assets or pass anvit.eval.corpusAssets."
            }

            val ingestionStarted = System.currentTimeMillis()
            corpusAssets.forEach { assetName ->
                val bytes = testAssets.open(assetName).use { it.readBytes() }
                when (val result = ingestion.ingestDocument(assetName, bytes, DEVICE_EVAL_COLLECTION_ID)) {
                    is IngestionResult.Success -> {
                        println("[DeviceEval] Ingested $assetName: ${result.chunkCount} chunks")
                    }
                    is IngestionResult.Error -> error("Failed to ingest $assetName: ${result.message}")
                }
            }
            val ingestionLatencyMs = System.currentTimeMillis() - ingestionStarted

            // Now that ingestion is complete and embeddings are persisted, load Gemma 4.
            // Doing this here keeps the heavy LLM out of memory during the slow embedding pass.
            val inference = deferredGemmaLoader?.invoke()
            if (inference != null) {
                println("[DeviceEval] Gemma loaded after ingestion (ingestionLatency=${ingestionLatencyMs}ms)")
            }

            val traceStarted = System.currentTimeMillis()
            val traces = dataset.samples.mapIndexed { index, sample ->
                val sampleStarted = System.currentTimeMillis()
                println("[DeviceEval] Running ${index + 1}/${dataset.samples.size}: ${sample.id} timeout=${sampleTimeoutMs}ms")
                val trace = try {
                    withTimeout(sampleTimeoutMs) {
                        if (generateAnswers) {
                            AgenticRagOrchestrator(requireNotNull(inference), retriever, db.documentDao()).processForEval(
                                queryId = sample.id,
                                userQuery = sample.question,
                                enableAgenticRag = enableAgenticRag,
                                maxChunks = maxChunks,
                                enableSelfCritique = enableSelfCritique,
                                useAgentTools = useAgentTools,
                                collectionId = DEVICE_EVAL_COLLECTION_ID
                            )
                        } else {
                            retrievalOnlyTrace(
                                queryId = sample.id,
                                userQuery = sample.question,
                                retriever = retriever,
                                maxChunks = maxChunks
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    error(
                        "Device eval sample timed out after ${sampleTimeoutMs}ms: ${sample.id}\n" +
                            "Question: ${sample.question}\n" +
                            "Try lowering -Panvit.eval.maxOutputTokens or disabling optional passes with " +
                            "-Panvit.eval.enableSelfCritique=false -Panvit.eval.useAgentTools=false"
                    )
                }
                println("[DeviceEval] Finished ${index + 1}/${dataset.samples.size}: ${sample.id} in ${System.currentTimeMillis() - sampleStarted}ms")
                DeviceEvalTraceRecord(
                    sampleId = sample.id,
                    trace = trace
                )
            }
            val traceLatencyMs = System.currentTimeMillis() - traceStarted

            val chunks = db.documentDao().getChunksForCollection(DEVICE_EVAL_COLLECTION_ID)
            val documents = db.documentDao().getAllDocuments().first()
                .filter { it.collectionId == DEVICE_EVAL_COLLECTION_ID }
                .map { document ->
                    val documentChunks = chunks.filter { it.docId == document.id }
                    DeviceEvalDocumentRecord(
                        docId = document.id,
                        fileName = document.fileName,
                        chunkCount = document.chunkCount,
                        status = document.status,
                        sizeBytes = document.sizeBytes,
                        contentHashes = documentChunks.map { stableEvalContentHash(it.content) }
                    )
                }

            val manifest = DeviceEvalManifest(
                runId = runId,
                datasetVersion = dataset.version,
                sampleCount = dataset.samples.size,
                corpusFiles = corpusAssets,
                gemmaModelId = evalModel?.id ?: "",
                gemmaModelFile = evalModel?.fileName ?: "",
                embeddingModelName = embedding.getModelName(),
                accelerator = accelerator,
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens,
                maxChunks = maxChunks,
                enableAgenticRag = enableAgenticRag,
                enableSelfCritique = enableSelfCritique,
                useAgentTools = useAgentTools,
                documents = documents,
                totalChunks = chunks.size,
                ingestionLatencyMs = ingestionLatencyMs,
                traceLatencyMs = traceLatencyMs
            )
            val answers = traces.map { DeviceEvalAnswer(it.sampleId, it.trace.generatedAnswer) }
            val artifacts = DeviceEvalRunArtifacts(
                runId = runId,
                generatedAt = Clock.System.now().toString(),
                manifest = manifest,
                traces = traces,
                answers = answers
            )

            val outputDir = File(appContext.filesDir, "device-eval/$runId")
            outputDir.mkdirs()
            File(outputDir, "device_ingestion_manifest.json").writeText(
                DeviceEvalJson.format.encodeToString(DeviceEvalManifest.serializer(), manifest)
            )
            File(outputDir, "device_trace.json").writeText(
                DeviceEvalJson.format.encodeToString(ListSerializer(DeviceEvalTraceRecord.serializer()), traces)
            )
            File(outputDir, "device_answers.json").writeText(
                DeviceEvalJson.format.encodeToString(ListSerializer(DeviceEvalAnswer.serializer()), answers)
            )
            File(outputDir, "device_eval_run.json").writeText(
                DeviceEvalJson.format.encodeToString(DeviceEvalRunArtifacts.serializer(), artifacts)
            )
            android.util.Log.i("DeviceEval", "Artifacts written to ${outputDir.absolutePath}")
            // Stream device_eval_run.json as base64-encoded chunks so the host can
            // reconstruct it via `adb logcat` even if the test runner uninstalls the
            // app (wiping app internal storage) before pullDeviceEvalArtifacts runs.
            // base64 avoids logcat-mangling from newlines/special chars in the JSON.
            val runJson = File(outputDir, "device_eval_run.json").readText()
            val b64 = android.util.Base64.encodeToString(runJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val artifactChunks = b64.chunked(2000)
            android.util.Log.i("DeviceEvalArtifact", "BEGIN $runId ${artifactChunks.size} ${b64.length}")
            artifactChunks.forEachIndexed { idx, chunk ->
                android.util.Log.i("DeviceEvalArtifact", "CHUNK $runId $idx $chunk")
            }
            android.util.Log.i("DeviceEvalArtifact", "END $runId")
            Unit
        } finally {
            db.close()
        }
    }

    private suspend fun retrievalOnlyTrace(
        queryId: String,
        userQuery: String,
        retriever: HybridRetriever,
        maxChunks: Int
    ): PipelineTrace {
        val started = System.currentTimeMillis()
        val retrieveStarted = System.currentTimeMillis()
        val chunks = retriever.retrieve(userQuery, maxChunks, DEVICE_EVAL_COLLECTION_ID)
        val traceChunks = chunks.map { TraceChunk.from(it) }
        val trace = PipelineTrace(
            queryId = queryId,
            userQuery = userQuery,
            route = "RETRIEVAL_ONLY",
            retrievedChunkIds = chunks.map { it.chunkId },
            dedupedChunkIds = chunks.distinctBy { it.chunkId }.map { it.chunkId },
            finalChunkIds = chunks.map { it.chunkId },
            retrievedChunks = traceChunks,
            finalChunks = traceChunks,
            vectorResultCount = chunks.count { it.vectorScore > 0f },
            lexicalResultCount = chunks.count { it.bm25Rank > 0 },
            ftsQueries = chunks.mapNotNull { it.ftsQuery }.distinct(),
            retrievalSources = chunks.groupingBy { it.retrievalSource }.eachCount(),
            generatedAnswer = "",
            latencyMs = mapOf("retrieve" to (System.currentTimeMillis() - retrieveStarted)),
            totalLatencyMs = System.currentTimeMillis() - started
        )
        println("[DeviceEval] Retrieval-only ${queryId}: ${chunks.size} chunks")
        return trace
    }

    private fun corpusAssets(configured: String?, rootAssets: List<String>): List<String> {
        val requested = configured
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return (requested.ifEmpty { rootAssets })
            .filter { it.endsWith(".pdf", ignoreCase = true) || it.endsWith(".docx", ignoreCase = true) }
            .sorted()
    }

    private fun resolveDeviceGemmaModel(
        requestedModelId: String,
        requestedModelFileName: String?,
        modelsDir: File
    ): GemmaModel? {
        val candidates = androidGemmaCandidates()
        if (!requestedModelFileName.isNullOrBlank()) {
            val explicit = candidates.firstOrNull { it.fileName == requestedModelFileName }
                ?: GemmaModels.defaultForPlatform(ios = false).copy(
                    id = "custom-device-gemma",
                    displayName = "Custom device Gemma",
                    fileName = requestedModelFileName
                )
            if (File(modelsDir, explicit.fileName).exists()) return explicit
        }
        candidates.firstOrNull { it.id == requestedModelId && File(modelsDir, it.fileName).exists() }
            ?.let { return it }
        return candidates.firstOrNull { File(modelsDir, it.fileName).exists() }
    }

    private fun androidGemmaCandidates(): List<GemmaModel> {
        val androidModels = GemmaModels.forPlatform(ios = false)
        val e2b = GemmaModels.E2B
        val e4b = GemmaModels.E4B
        return androidModels + listOf(
            e2b.copy(
                id = "gemma4-e2b-legacy-int4",
                displayName = "${e2b.displayName} (legacy int4 filename)",
                fileName = "gemma4-e2b-it-int4.litertlm"
            ),
            e4b.copy(
                id = "gemma4-e4b-legacy-int4",
                displayName = "${e4b.displayName} (legacy int4 filename)",
                fileName = "gemma4-e4b-it-int4.litertlm"
            )
        )
    }

    private companion object {
        const val DEVICE_EVAL_COLLECTION_ID = "device-eval-collection"
        const val DEFAULT_EVAL_CONTEXT_WINDOW = 4096
        const val DEFAULT_EVAL_MAX_OUTPUT_TOKENS = 128
        const val DEFAULT_SAMPLE_TIMEOUT_MS = 60_000L
    }
}
