# Agentic System Architecture

Anvit represents a paradigm shift from traditional "dumb" RAG (Retrieval-Augmented Generation) to an **Agentic RAG** system that actively reasons about user queries, evaluates its own retrieved context, and refines its answers iteratively—all completely on-device.

## App Theme Colors

Material3 `primary` maps to the Anvit `accent` token, and Material3 `secondary` maps to the Anvit `txt1` token.

- Dark / Midnight theme: primary `#00C8E8`, secondary `#8BAFC8`
- Light / Dawn theme: primary `#0099BA`, secondary `#2A5068`

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

# [AgenticRAG] recent context, 2026-05-19 10:48pm GMT+5:30

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (21,997t read) | 302,418t work | 93% savings

### May 6, 2026
S46 Fix device evaluation test failing with missing Gemma model files on Android device (May 6 at 9:32 AM)
S47 User asked whether to use USB debugging instead of wireless debugging for their phone development setup (May 6 at 9:42 AM)
S48 Fixed deprecated Project.android accessor in device eval model backup/restore gradle tasks (May 6 at 9:46 AM)
S49 Configure project :app to resolve build failure with missing connectedDebugAndroidTest task (May 6 at 9:48 AM)
S50 Increment version for new release of AgenticRAG Android app (May 6 at 10:42 AM)
S51 Determine correct Android foreground service permissions for on-device LLM app using FOREGROUND_SERVICE_DATA_SYNC and FOREGROUND_SERVICE_SPECIAL_USE (May 6 at 2:02 PM)
S52 Diagnose Gemma 4 model loading failure after recent app changes (May 6 at 2:11 PM)
S53 Fix inability to load Gemma 4 model after recent code changes in AgenticRAG Android app (May 6 at 4:11 PM)
S54 Update request received - status checkpoint (May 6 at 4:19 PM)
### May 13, 2026
S55 Validate evaluation metrics after judge output fix by comparing runs 20260512-102523 and 20260512-135717 (May 13 at 12:54 AM)
### May 19, 2026
472 1:26p 🟣 OCR-specific document ingestion path in DocumentIngestionService
473 1:27p 🟣 PaddleOcrChunker implementation with heading inference and table detection
474 1:28p 🟣 Android-specific PaddleOCR PDF parser with RapidOCR integration
475 " 🟣 Integrated PaddleOCR pipeline into Android dependency injection and build
478 1:36p 🟣 PaddleOCR chunking successfully exported on test documents
480 1:43p 🔵 PaddleOCR chunked output contains structured text and table extraction
481 1:45p 🔵 PaddleOCR table extraction preserves complex financial data structure and formatting
482 1:47p 🔵 PaddleOCR document chunking creates hierarchical structure with proper parent-child relationships
483 " 🟣 PaddleOCR chunker enhanced with structure normalization and table parsing for language model comprehension
484 1:52p 🔴 Fixed null pointer and column clustering tolerance in table structure parsing
485 " 🟣 Added comprehensive integration test for RIL financial table chunking with structure validation
486 1:53p 🔴 Fixed compilation error from duplicate tableLineIds variable declaration
487 " 🔵 Root cause identified: duplicate tableLineIds declarations in same scope
488 " 🔴 Removed duplicate mutable tableLineIds declaration to resolve compilation error
489 " 🔵 Test execution reveals logic failures in table detection and parsing
490 " 🔵 Test failure root causes identified from JUnit XML report
491 1:54p 🔵 Actual chunk output reveals table detection completely disabled by improved logic
492 " 🔵 Table detection partially works but misses critical page 1 financial table
493 " 🔵 Page 1 financial table content folded into TEXT chunk instead of recognized as TABLE
494 " 🔴 Improved serial number detection in table rows with position-aware logic
495 1:55p 🔵 Serial number detection fix applied but page 1 table still not detected
496 " 🔵 Confirmed: zero TABLE chunks on page 1; isTableStartRow() heuristic too strict
497 " 🔵 isTableStartRow() heuristic actually works correctly; root cause identified as parsing phase
498 1:56p 🔵 Page 1 table still not emitted despite detection logic working; parsing failure confirmed
499 " 🟣 Implemented Complete First-Page Table Detection and Structure Improvements for PaddleOCR Chunker
500 6:30p 🔵 Current chunk export implementation exports JSON, CSV, and summary markdown
501 6:31p 🟣 Added markdown chunk export with individual chunk sections
502 " 🔵 Markdown chunk export test passes with minor type warning
503 " 🟣 Markdown chunk export generates properly formatted files with sequential chunk numbering
504 6:49p 🔵 Chunk 6 OCR formatting issues identified in financial document
505 " 🔵 Root cause identified: OCR spacing detection and line structure loss in chunking pipeline
506 6:51p 🔄 Refactor PaddleOcrChunker text formatting and heading detection
507 6:52p 🔴 Fix regex pattern matching in formatTextLines method
508 " 🔵 PaddleOcrChunkExportTest unit tests pass with refactored chunker
509 " 🔵 Paddle OCR chunker processes RIL financial document with improved formatting
510 " 🔵 Paddle OCR output reveals chunking handles OCR quality issues and CEO commentary
511 6:53p ✅ Enhance PaddleOcrChunker bullet detection and text normalization
512 6:54p 🔵 Enhanced PaddleOcrChunker tests pass with improved performance
513 " 🔵 Text normalization improvements visible in re-processed chunks
514 " 🔵 Paddle OCR chunker implementation complete with comprehensive text processing pipeline
515 6:55p 🔵 Raw Paddle OCR output analysis from RIL page 2 reveals specific text artifacts
516 " ✅ Enhance formatTextLines with bullet tracking and metric line detection
517 6:56p 🔵 Enhanced chunker with metric line detection produces improved formatting and readability
518 " 🔵 Paddle OCR chunker test output validates improvements with identified remaining gaps
519 " ✅ Refine CIN (Corporate Identification Number) detection in furniture filtering
520 6:57p ✅ Add additional text normalization patterns for numeric variations and spacing gaps
521 " ✅ Add integration test for Annual Performance section with bullet structure validation
522 6:58p 🔴 CIN regex pattern in isPageFurniture() breaks existing tests
523 " 🔴 Test failures root cause: broken CIN regex breaks furniture filtering and table parsing
524 " ✅ Update table format assertions to match markdown table output

Access 302k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>
