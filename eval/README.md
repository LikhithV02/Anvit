# Anvit Agentic RAG Evaluation

This directory contains the pinned offline evaluation assets for Anvit's agentic RAG pipeline.

The canonical quality run is the device-local eval: document parsing, chunking,
embeddings, retrieval, agentic routing/decomposition/CRAG, Gemma generation,
native tools, and self-critique run on a connected Android device with local
models. Gemini is used only afterward as the external answer judge.

## Commands

- `./gradlew :app:connectedDeviceEval` runs the device-local pipeline on a connected Android device and writes JSON artifacts under app external files.
- `./gradlew :composeApp:scoreDeviceEval -Danvit.eval.deviceArtifacts=<pulled-run-dir>` scores pulled device artifacts with retrieval metrics and Gemini answer judging.
- `./gradlew :composeApp:runCloudBackedEval` runs the older Gemini-backed JVM/Robolectric eval harness against the pinned dataset.
- `./gradlew :composeApp:regenerateDataset` builds `eval/datasets/v1/dataset.json` from exported chunk CSVs in `Test Docs/`.
- `./gradlew :composeApp:refreshBaseline` writes `eval/baseline.json` from the current run.

`connectedDeviceEval` does not use Gemini. `scoreDeviceEval`, `runCloudBackedEval`,
`regenerateDataset`, and `refreshBaseline` require `GEMINI_API_KEY`.

## Gemini API Key

Create a Gemini API key in Google AI Studio, then export it before running eval:

```bash
export GEMINI_API_KEY="your_api_key_here"
```

To keep it available in new terminal sessions on macOS/zsh:

```bash
echo 'export GEMINI_API_KEY="your_api_key_here"' >> ~/.zshrc
source ~/.zshrc
```

Do not commit API keys to the repo. CI reads the same variable from the `GEMINI_API_KEY` repository secret.

## Running Device-Local Evaluation

Install/download the local models in the app first. At minimum the device needs
the selected Gemma 4 model plus either EmbeddingGemma or Gecko initialized from
Settings.

Run a smoke pass on a connected physical device:

```bash
./gradlew :app:connectedDeviceEval -Panvit.eval.sampleLimit=2
```

Run the full pinned dataset:

```bash
./gradlew :app:connectedDeviceEval
```

Optional instrumentation arguments can be passed as Gradle properties, for
example:

```bash
./gradlew :app:connectedDeviceEval \
  -Panvit.eval.modelId=gemma4-e2b \
  -Panvit.eval.accelerator=cpu \
  -Panvit.eval.maxChunks=5 \
  -Panvit.eval.useAgentTools=true
```

The device writes:

- `device_trace.json`
- `device_ingestion_manifest.json`
- `device_answers.json`
- `device_eval_run.json`

Pull the latest run from the device:

```bash
adb shell ls /sdcard/Android/data/com.likhith.anvit/files/device-eval
adb pull /sdcard/Android/data/com.likhith.anvit/files/device-eval/<run-id> eval/device-runs/<run-id>
```

Score the pulled artifacts with Gemini as judge:

```bash
export GEMINI_API_KEY="your_api_key_here"
./gradlew :composeApp:scoreDeviceEval \
  -Danvit.eval.deviceArtifacts="$PWD/eval/device-runs/<run-id>"
```

## Running Cloud-Backed JVM Evaluation

Use the Android Studio JBR on this machine if Gradle cannot find Java:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Run the older Gemini-backed eval dataset:

```bash
./gradlew :composeApp:runCloudBackedEval
```

Regenerate the synthetic dataset from the existing chunk CSV exports:

```bash
./gradlew :composeApp:regenerateDataset
```

Refresh the baseline after an intentional retrieval/answer-quality improvement:

```bash
./gradlew :composeApp:refreshBaseline
```

Reports are written under `eval/reports/<timestamp>/`:

- `summary.md` for top-line CI/comment output
- `details.html` for per-question drill-down
- `metrics.json` for machine-readable regression checks

Embedding and judge responses are cached under `eval/.cache/` by default so repeated runs are cheaper. Override the cache location when needed:

```bash
./gradlew :composeApp:runCloudBackedEval -Danvit.eval.cacheDir=/tmp/anvit-eval-cache
```

## Batch Processing

Answer judging uses the Gemini Batch API by default. For device-local eval,
batch mode is used only after the device has exported traces and answers. For
cloud-backed JVM eval, the harness first runs the Anvit pipeline to collect
traces, then submits all judge prompts as one inline batch job against the judge
model. This is slower than immediate calls because batch jobs are asynchronous,
but it is designed for non-urgent evaluation workloads and reduces judge-call
cost.

Reference: [Gemini Batch API documentation](https://ai.google.dev/gemini-api/docs/batch-mode).

Disable batch judging for quick local debugging:

```bash
./gradlew :composeApp:scoreDeviceEval \
  -Danvit.eval.deviceArtifacts="$PWD/eval/device-runs/<run-id>" \
  -Danvit.eval.disableBatchJudge=true
```

In the cloud-backed JVM eval, the pipeline-under-test still uses immediate
Gemini calls for router/decomposer/CRAG/generation because each stage depends on
the previous stage's result. In the device-local eval, those stages run with the
local Gemma service on Android. Batch mode is applied where requests are
independent: the final answer-judge pass.

## Runtime Shape

The cloud-backed harness runs on the JVM unit-test surface and reuses the
production ingestion, chunking, retrieval, and orchestration code. It swaps only
the inference and embedding services for Gemini-backed test implementations so
CI can run without Android NN/LiteRT runtime dependencies.

The device-local harness runs as an Android instrumentation test against the
real app process, uses production Android local model services, exports
`PipelineTrace`, and then scores those traces with the same retrieval metric and
reporting code.
