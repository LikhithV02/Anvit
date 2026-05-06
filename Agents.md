# Agentic System Architecture

Anvit represents a paradigm shift from traditional "dumb" RAG (Retrieval-Augmented Generation) to an **Agentic RAG** system that actively reasons about user queries, evaluates its own retrieved context, and refines its answers iteratively—all completely on-device.

## Agentic RAG Pipeline Visualization

The following diagram illustrates the lifecycle of a query as it traverses Sage's agentic orchestrator.

```mermaid
flowchart TD
    UserQuery([User Query])

    %% Routing
    Router{Query Router}
    UserQuery --> Router

    Router -->|SINGLE_SHOT| SingleRet[Standard Hybrid Retrieval]
    Router -->|AGENTIC| Decomposer[Query Decomposer]

    %% Single-Shot Flow
    SingleRet --> SingleRed[Content Reducer]
    SingleRed --> SingleGen[LLM Generation]

    %% Agentic Flow
    Decomposer --> SubQ[multiple sub-queries]
    SubQ --> AgenticRet[(Hybrid Retrieval)]
    AgenticRet --> MergeRRF[Merge & Deduplicate]

    MergeRRF --> Evaluator{Relevance Evaluator}

    Evaluator -->|REQUERY| Rephrase[Rephrase Query]
    Rephrase --> AgenticRet

    Evaluator -->|SUPPLEMENT| SuppRet[(Supplemental Retrieval)]
    SuppRet --> MergeSupp[Merge]
    MergeSupp --> Reducer

    Evaluator -->|USE| Reducer[Selective Content Reducer]

    Reducer --> AgenticGen[LLM Generation with Native Tools]

    %% Self-Critique Flow
    AgenticGen --> Critique{Self-Critique Loop}
    Critique -->|INSUFFICIENT| GapQuery[Generate Gap Query]
    GapQuery --> ExtraRet[(Gap Retrieval)]
    ExtraRet --> RefinedGen[Refined Generation]

    Critique -->|SUFFICIENT| FinalResponse

    %% Terminations
    SingleGen --> FinalResponse([Final Response])
    RefinedGen --> FinalResponse
```

## Core Agentic Components

All components are located within the `app/src/main/java/com/anvit/localai/agentic/` package.

### 1. Query Router (`QueryRouter.kt`)
The router acts as the front door, determining the computational path needed for a given user prompt.
- **SINGLE_SHOT**: Fact-finding questions with clear keywords where simple hybrid retrieval is sufficient.
- **AGENTIC**: Complex, multi-part, or comparative queries that require the full pipeline.

### 2. Query Decomposer (`QueryDecomposer.kt`)
Complex questions typically dilute dense vector retrievals. The Decomposer asks the LLM to break a complex prompt (e.g., "Compare the safety findings in doc A and doc B") into discrete sub-queries (e.g., "What are safety findings in doc A?", "What are safety findings in doc B?").

### 3. Relevance Evaluator (CRAG) (`RelevanceEvaluator.kt`)
Implements Corrective RAG (CRAG). Before feeding retrieved chunks to the generation LLM, a lightweight evaluator checks the semantic relevance of the retrieved context against the user query.
- **USE**: The context is highly relevant. Proceed to generation.
- **SUPPLEMENT**: The context is okay but might be missing broader details. Triggers an additional broad retrieval.
- **REQUERY**: The context is irrelevant. Triggers a LLM rephrasing of the search query and a retry (up to 2 times).

### 4. Selective Content Reducer (`SelectiveContentReducer.kt`)
Raw context chunks often contain noise. This heuristic layer trims trailing sections, removes redundancy, and ensures that the final structured context strings are as token-efficient as possible before context injection (~30% reduction in tokens without losing facts).

### 5. Native Tool Calling (`RagAgentTools.kt`)
Rather than relying purely on pre-retrieved context, the pipeline exposes native Android tools to the local Gemma 4 model (like `search_documents`, `get_document_section`). The model can autonomously choose to suspend generation, invoke a tool, and resume with newly fetched data.

### 6. Self-Critique Loop (`SelfCritiqueLoop.kt`)
After the first response is generated, an independent critique LLM pass evaluates the generated response against the original question and the retrieved context. If it detects that the answer is lacking specifics or hallucinated (e.g., "The document doesn't say..."), it produces a gap query. This gap query kicks off a targeted retrieval and an addendum answer generation stringing the correct final facts.


<claude-mem-context>
# Memory Context

# [AgenticRAG] recent context, 2026-05-06 1:11pm GMT+5:30

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (16,704t read) | 278,656t work | 94% savings

### May 3, 2026
S40 Finalize and validate table formatting decision; confirm key-value format is producing quality chunks in actual document ingestion pipeline (May 3 at 10:00 PM)
S41 Evaluate table formatting approach for RAG chunking and validate pipeline output quality; determine whether to switch from key-value to markdown table syntax (May 3 at 10:01 PM)
S42 Install mobile app, download models, and run smoke test validation (May 3 at 10:01 PM)
### May 6, 2026
133 8:55a 🔵 RetrievalMetrics scoring implementation examined
134 9:00a 🔵 Device evaluation requires pre-downloaded Gemma 4 models on test device
135 " 🔵 Device evaluation test accepts parametrized configuration and supports multiple Gemma models
136 9:01a 🔵 App not installed on test device; models directory cannot be accessed
S43 Fix concurrent LiteRT Engine issue in device eval test by reusing Koin singleton; retry smoke test validation (May 6 at 9:18 AM)
137 9:19a 🔵 Baseline evaluation metrics established for Agentic RAG system
138 9:21a ✅ Device eval test refactored to reuse Koin InferenceService singleton instead of creating new GemmaInferenceService instance
S44 Build a production-grade evaluation pipeline for Agentic RAG in Anvit with automated LLM-based evaluation and CI/CD integration for PR feedback (May 6 at 9:23 AM)
139 9:24a ✅ Added HTML comment marker to eval markdown reports
140 9:25a 🟣 Automated evaluation results posting to pull requests
141 " 🔄 Removed simple ID-based metric functions in favor of relevance-based variants
142 9:26a 🟣 Migrated GeminiClient from curl-based to Ktor HTTP client with streaming support
143 " ✅ Integrated streaming API into GeminiInferenceService and added Ktor dependency
S45 Diagnose Gemma model load failure in device instrumented test and improve error reporting (May 6 at 9:26 AM)
144 9:30a 🔵 Gemma 4 E2B model fails to load during device instrumented test
145 9:31a ✅ Added loadModelOrThrow variant to expose model loading exceptions
146 9:32a ✅ Enhanced device test error reporting with root cause details for model loading
S46 Fix device evaluation test failing with missing Gemma model files on Android device (May 6 at 9:32 AM)
147 9:42a 🔴 Model file persistence during device eval test runs
S47 User asked whether to use USB debugging instead of wireless debugging for their phone development setup (May 6 at 9:42 AM)
S48 Fixed deprecated Project.android accessor in device eval model backup/restore gradle tasks (May 6 at 9:46 AM)
148 10:33a 🔵 Gradle wrapper lock file permission denied blocks :app:installDebug
149 10:34a 🔵 app/build.gradle.kts has unresolved exec references and deprecated Android DSL syntax
150 " 🔴 Fixed unresolved exec references in app/build.gradle.kts using providers.exec API
151 " 🔵 Build configuration fixed, app compilation and packaging successful
152 10:35a 🔴 ./gradlew :app:installDebug completed successfully with APK installed to device
153 " ✅ app/build.gradle.kts extended with device evaluation and model persistence infrastructure
154 10:42a 🔵 Build script assumes connectedDebugAndroidTest task but it's not registered
S49 Configure project :app to resolve build failure with missing connectedDebugAndroidTest task (May 6 at 10:42 AM)
155 10:44a 🔴 Gradle adb command resolution from Android SDK path
156 " 🔵 Gradle script compilation errors from missing Java imports
157 10:45a 🔴 Added Java imports and fixed lambda type inference in adbExecutable provider
158 " 🔵 Gradle wrapper lock file permission issue blocks build execution
159 " 🔵 adbExecutable provider successfully resolves and invokes adb command
160 " 🔵 Device-based instrumented tests now execute successfully using adb after path resolution fix
161 10:46a 🔴 Fixed Gemma model loading failure by disabling vision/audio backends in device eval
162 " 🔵 Device eval test executes successfully after adb path and model loading fixes
163 10:47a ✅ Refactored backupEvalModels task to use adb exec-out with cat for reliable model file backup
164 10:48a 🔵 Refactored backupEvalModels task executes successfully with ProcessBuilder/adb exec-out implementation
165 " 🔵 adb Command Not Found Error Persists in backupEvalModels Task
166 10:52a 🔴 adb Command Resolution Fixed in backupEvalModels Task
167 10:55a 🔵 App File Structure and Model Storage Investigation
168 10:56a 🔵 backupEvalModels Task Cannot Locate Existing Model Files
169 " 🔴 Fixed backupEvalModels Model File Discovery via adb
170 " 🔵 backupEvalModels Now Discovers Model Files Successfully
171 10:57a 🔵 backupEvalModels Successfully Pulls Both Model Files
172 10:58a 🔵 backupEvalModels Task Completed Successfully
173 11:01a 🔵 backupEvalModels Transfer In Progress - adb Successfully Reading Model Files
174 11:02a 🔵 backupEvalModels Task Completed After Fix Validation
175 11:12a ✅ Made model backup/restore optional in device eval pipeline
176 11:13a 🔵 Verified conditional task graph wiring for model backup pipeline
177 11:28a 🔵 ConcurrentModificationException in ARouter blocking device evaluation
178 " 🔵 Device eval test active on first sample, models loaded, ARouter crashes blocking progress
179 11:29a 🔵 processForEval() blocks indefinitely on answer stream without timeout
180 " ✅ Added sampleTimeoutMs parameter to device eval instrumentation manifest
181 " 🔵 Android Gradle Plugin Deprecated Settings and APIs in AgenticRAG Build
182 " 🔵 Kotlin Compiler Warning in AndroidTest: Supertype Access Visibility Issue

Access 279k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>