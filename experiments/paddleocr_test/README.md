# PaddleOCR Android Experiment

This is an isolated Android/Kotlin OCR experiment. It is intentionally not wired
into `composeApp`, app navigation, document ingestion, or shared production code.

The runner uses `rapidocr4j-android`, an Android AAR around RapidOCR's
PaddleOCR-derived ONNX pipeline. This validates Android runtime compatibility
without taking a dependency in the app itself.

## Build

```bash
./gradlew -p experiments/paddleocr_test assembleDebug
```

## Run

```bash
./gradlew -p experiments/paddleocr_test installDebug
adb shell monkey -p com.anvit.experiments.paddleocr 1
adb logcat -s PaddleOcrExperiment
```

When the run completes, outputs are written on device at:

```text
/sdcard/Android/data/com.anvit.experiments.paddleocr/files/ocr_outputs/
```

Pull them back into the repo with:

```bash
mkdir -p experiments/paddleocr_test/outputs
adb pull /sdcard/Android/data/com.anvit.experiments.paddleocr/files/ocr_outputs/. experiments/paddleocr_test/outputs/
```
