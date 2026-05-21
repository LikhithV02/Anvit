package com.anvit.localai.eval.runner

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anvit.localai.agentic.AgenticRagOrchestrator
import com.anvit.localai.data.db.AnvitDatabase
import com.anvit.localai.document.DesktopPdfHierarchicalParser
import com.anvit.localai.document.DocumentIngestionService
import com.anvit.localai.document.DocxHierarchicalParser
import com.anvit.localai.document.IngestionResult
import com.anvit.localai.document.PdfExtractionResult
import com.anvit.localai.document.PdfExtractor
import com.anvit.localai.eval.dataset.EvalDataset
import com.anvit.localai.eval.metrics.AnswerJudge
import com.anvit.localai.eval.metrics.MetricsAggregator
import com.anvit.localai.eval.metrics.PerfScore
import com.anvit.localai.eval.metrics.RetrievalMetrics
import com.anvit.localai.eval.report.MarkdownReporter
import com.anvit.localai.eval.report.HtmlReporter
import com.anvit.localai.eval.services.GeminiClient
import com.anvit.localai.eval.services.GeminiEmbeddingService
import com.anvit.localai.eval.services.GeminiInferenceService
import com.anvit.localai.retrieval.HybridRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import java.io.File

class EvalHarness(
    private val client: GeminiClient,
    private val datasetFile: File,
    private val corpusDir: File,
    private val outputDir: File,
    private val maxChunks: Int = 5
) {
    suspend fun run(): EvalRunResult {
        val dataset = EvalDataset.load(datasetFile)
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnvitDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            ingestCorpus(db)
            println("[EvalHarness] Corpus ingestion complete. Starting pipeline traces for ${dataset.samples.size} samples.")
            val inference = GeminiInferenceService(client)
            val retriever = HybridRetriever(db.documentDao(), GeminiEmbeddingService(client).also { it.initialize() })
            val orchestrator = AgenticRagOrchestrator(inference, retriever, db.documentDao())
            val judge = AnswerJudge(client)
            val traceWorkers = (System.getProperty("anvit.eval.traceWorkers")?.toIntOrNull() ?: 4).coerceIn(1, 8)
            val judgeWorkers = (System.getProperty("anvit.eval.judgeWorkers")?.toIntOrNull() ?: 4).coerceIn(1, 8)

            val traces = coroutineScope {
                val semaphore = Semaphore(traceWorkers)
                dataset.samples.mapIndexed { index, sample ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            println("[EvalHarness] Running ${index + 1}/${dataset.samples.size}: ${sample.id}")
                            sample to orchestrator.processForEval(
                                queryId = sample.id,
                                userQuery = sample.question,
                                enableAgenticRag = true,
                                maxChunks = maxChunks,
                                enableSelfCritique = true,
                                useAgentTools = false,
                                collectionId = "default-collection"
                            )
                        }
                    }
                }.awaitAll()
            }
            println("[EvalHarness] Pipeline traces complete: ${traces.size}/${dataset.samples.size}. Starting answer judging.")
            val judgeScores = if (System.getProperty("anvit.eval.disableBatchJudge", "false").toBoolean()) {
                println("[EvalHarness] Batch judging disabled. Running immediate judge with $judgeWorkers workers.")
                coroutineScope {
                    val semaphore = Semaphore(judgeWorkers)
                    traces.mapIndexed { index, item ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val (sample, trace) = item
                                println("[EvalHarness] Judging ${index + 1}/${traces.size}: ${sample.id}")
                                sample.id to judge.score(sample, trace)
                            }
                        }
                    }.awaitAll().toMap()
                }
            } else {
                println("[EvalHarness] Batch judging enabled. Submitting ${traces.size} judge prompts.")
                runCatching { judge.scoreBatch(traces) }
                    .getOrElse { error ->
                        println("[EvalHarness] Batch judge failed, falling back to immediate judge: ${error.message}")
                        coroutineScope {
                            val semaphore = Semaphore(judgeWorkers)
                            traces.mapIndexed { index, item ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val (sample, trace) = item
                                        println("[EvalHarness] Judging ${index + 1}/${traces.size}: ${sample.id}")
                                        sample.id to judge.score(sample, trace)
                                    }
                                }
                            }.awaitAll().toMap()
                        }
                    }
            }
            println("[EvalHarness] Answer judging complete: ${judgeScores.size}/${traces.size} scores. Computing retrieval/perf metrics.")

            val scored = traces.map { (sample, trace) ->
                ScoredEvalSample(
                    sample = sample,
                    trace = trace,
                    retrieval = RetrievalMetrics.score(sample, trace),
                    answer = judgeScores[sample.id] ?: judge.score(sample, trace),
                    perf = PerfScore.from(trace)
                )
            }

            val result = EvalRunResult(
                runId = outputDir.name,
                gitSha = System.getProperty("anvit.eval.gitSha") ?: "unknown",
                generatedAt = Clock.System.now().toString(),
                metadata = client.evalMetadata() + mapOf(
                    "dataset" to datasetFile.absolutePath,
                    "dataset_version" to dataset.version
                ),
                summary = MetricsAggregator.summarize(scored),
                samples = scored
            )
            outputDir.mkdirs()
            println("[EvalHarness] Writing eval report to ${outputDir.absolutePath}")
            File(outputDir, "metrics.json").writeText(EvalDataset.json.encodeToString(EvalRunResult.serializer(), result))
            MarkdownReporter.write(File(outputDir, "summary.md"), result)
            HtmlReporter.write(File(outputDir, "details.html"), result)
            println("[EvalHarness] Report written: metrics.json, summary.md, details.html")
            return result
        } finally {
            db.close()
        }
    }

    private suspend fun ingestCorpus(db: AnvitDatabase) {
        val files = corpusDir.listFiles { file ->
            file.isFile && (file.extension.equals("pdf", true) || file.extension.equals("docx", true))
        }.orEmpty()
        require(files.isNotEmpty()) { "No PDF or DOCX files found in ${corpusDir.absolutePath}" }
        println("[EvalHarness] Ingesting corpus from ${corpusDir.absolutePath}: ${files.size} files")

        val ingestion = DocumentIngestionService(
            documentDao = db.documentDao(),
            embeddingService = GeminiEmbeddingService(client).also { it.initialize() },
            pdfExtractor = object : PdfExtractor {
                override suspend fun extract(fileName: String, documentBytes: ByteArray): PdfExtractionResult? = null
            },
            documentParsers = listOf(DesktopPdfHierarchicalParser(), DocxHierarchicalParser())
        )
        var totalChunks = 0
        files.forEachIndexed { index, file ->
            val started = System.currentTimeMillis()
            println("[EvalHarness] Ingesting ${index + 1}/${files.size}: ${file.name} (${file.length()} bytes)")
            when (val result = ingestion.ingestDocument(file.name, file.readBytes(), "default-collection")) {
                is IngestionResult.Success -> {
                    totalChunks += result.chunkCount
                    println("[EvalHarness] Ingested ${index + 1}/${files.size}: ${file.name}: ${result.chunkCount} chunks in ${elapsedSeconds(started)}s")
                }
                is IngestionResult.Error -> error("Failed to ingest ${file.name}: ${result.message}")
                IngestionResult.Cancelled -> error("Ingestion cancelled for ${file.name}")
            }
        }
        println("[EvalHarness] Ingestion summary: ${files.size} files, $totalChunks chunks")
    }

    private fun elapsedSeconds(started: Long): String =
        "%.1f".format((System.currentTimeMillis() - started) / 1_000.0)
}
