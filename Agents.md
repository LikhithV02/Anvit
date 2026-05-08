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

# [AgenticRAG] recent context, 2026-05-08 1:38am GMT+5:30

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (15,965t read) | 289,219t work | 94% savings

### May 6, 2026
S44 Build a production-grade evaluation pipeline for Agentic RAG in Anvit with automated LLM-based evaluation and CI/CD integration for PR feedback (May 6 at 9:23 AM)
S45 Diagnose Gemma model load failure in device instrumented test and improve error reporting (May 6 at 9:26 AM)
S46 Fix device evaluation test failing with missing Gemma model files on Android device (May 6 at 9:32 AM)
S47 User asked whether to use USB debugging instead of wireless debugging for their phone development setup (May 6 at 9:42 AM)
S48 Fixed deprecated Project.android accessor in device eval model backup/restore gradle tasks (May 6 at 9:46 AM)
S49 Configure project :app to resolve build failure with missing connectedDebugAndroidTest task (May 6 at 9:48 AM)
S50 Increment version for new release of AgenticRAG Android app (May 6 at 10:42 AM)
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
183 1:11p 🔵 Test failure root cause investigation via logcat analysis
184 " 🔵 SELinux permission denials blocking hwservicemanager access in test process
185 1:12p 🔵 Instrumentation process crash: SIGSEGV null pointer dereference in LiteRT JNI library
186 " 🔵 Android instrumented test crash on Motorola Edge 40 Neo device
187 " 🟣 Added retrieval-only evaluation mode to device tests
188 1:13p 🔴 Fixed PipelineTrace construction in retrievalOnlyTrace method
189 " 🔵 Compilation warning: ToolSet supertype visibility issue in device eval test
190 1:14p 🟣 Optimized device eval to skip inference initialization in retrieval-only mode
191 " 🔴 Fixed syntax error in device eval test from patch application
192 " 🔴 Resolved compilation error in device eval test
193 1:15p 🔵 Device eval test executed but failed due to missing Gemma model on device
194 1:16p 🟣 Made Gemma model requirement conditional on answer generation mode
195 " 🔴 Device eval test compilation successful with conditional model requirement
196 " 🟣 Conditional LiteRT model inclusion in device eval APK based on evaluation mode
197 2:02p ✅ Version incremented for new release
S51 Determine correct Android foreground service permissions for on-device LLM app using FOREGROUND_SERVICE_DATA_SYNC and FOREGROUND_SERVICE_SPECIAL_USE (May 6 at 2:02 PM)
S52 Diagnose Gemma 4 model loading failure after recent app changes (May 6 at 2:11 PM)
198 4:10p 🔵 Android model loading path and recent version bump
199 " 🔵 Large build configuration changes in recent commit
200 4:12p 🔵 Gemma 4 E2B model loading fails in Settings page
201 4:13p 🔵 LiteRT dependencies unchanged in latest commit
202 " 🔵 ProGuard rules configured for newly added document parsing libraries
203 4:14p 🔵 No Gemma/LiteRT model loading logs in device logcat
204 " 🔵 Logcat confirms no AgenticRAG or LiteRT activity on device
205 4:17p 🔵 Gemma-4 model TensorFlow Lite components loading successfully in logcat
206 4:18p 🔴 Fixed Gemma 4 model loading failure caused by multiple vision encoder signatures
S53 Fix inability to load Gemma 4 model after recent code changes in AgenticRAG Android app (May 6 at 4:19 PM)
**Investigated**: Examined Android app logs from ADB logcat to identify model loading failures. Found stack trace showing LiteRT-LM JNI native method nativeCreateEngine() throwing INVALID_ARGUMENT exception during Engine.initialize() in GemmaInferenceService.ensureEngineLoaded().

**Learned**: The Gemma 4 E2B model file ships with a multi-scale Vision Encoder containing 3 signatures (vision_70, vision_140, vision_280), but LiteRT-LM v0.10.2's Engine class enforces a single-signature constraint for the Vision Encoder component. Conditional backend initialization based on model.supportsVision and model.supportsAudio flags was triggering initialization of these multiple encoder signatures, causing the signature mismatch error.

**Completed**: Root cause diagnosed. Code fix implemented in GemmaInferenceService.kt: disabled conditional vision/audio backend initialization by setting visionBackend=null and audioBackend=null unconditionally in EngineConfig. Updated logging to remove vision/audio capability flags. Built and installed debug APK to Motorola Edge 40 Neo device (26s build time, BUILD SUCCESSFUL).

**Next Steps**: Testing model loading functionality - app should now successfully load Gemma 4 model with text inference working (vision/audio features disabled). User to verify by opening app and tapping Load model.


Access 289k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>