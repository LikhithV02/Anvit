# iOS OOM Crash – Research Context

## Problem Summary

The AnvitApp iOS app crashes when loading the `gemma-4-e2b-it-4bit` model on a physical iPhone 12 Pro. The OS kills the process with a jetsam OOM (out-of-memory) event before the model finishes loading.

---

## Device & Environment

| Property | Value |
|----------|-------|
| Device | iPhone 12 Pro (`iPhone13,2`) |
| Physical RAM | 6 GB |
| iOS version | 26.4.2 (23E261) |
| Xcode | 26.4.1 (Build 17E202) |
| SDK | iphoneos26.4 |
| Architecture | arm64 |

---

## Crash Signature

```
EXC_RESOURCE
RESOURCE_TYPE_MEMORY: high watermark memory limit exceeded
limit = 2348 MB
```

This is a **jetsam kill** — iOS terminated the process because it exceeded the per-process memory high-watermark limit of **2348 MB** (~2.3 GB). This is not a code bug; it is the OS enforcing memory limits.

---

## Model Being Loaded

| Property | Value |
|----------|-------|
| Model name | `gemma-4-e2b-it-4bit` |
| Architecture | `gemma4` with `vision_config` present → **VLM** (Vision-Language Model) |
| Weight file | `model.safetensors` — **3.3 GB on disk** |
| In-memory footprint | ~3.3 GB (safetensors maps 1:1 to wired GPU memory in MLX) |
| Quantization | 4-bit for language backbone; vision encoder layers may be float16 |
| Factory used | `VLMModelFactory` (loads both language model + vision encoder / SigLIP) |

### Why 3.3 GB for a "2B" model?

- `E2B` = Efficient 2B refers to the **text backbone** only
- The full Gemma 4 E2B VLM includes a vision encoder (SigLIP-400M or equivalent) stored at float16 (~400–800 MB on top of the 4-bit language weights)
- Some non-quantizable layers (embedding, norm, lm_head) remain in float16
- MLX's 4-bit packing format stores scales/biases per group, so effective bits-per-param > 4

---

## Root Causes (layered)

### 1. MLX buffer cache uncapped (primary)
MLX's intermediate tensor cache defaults to `GPU.maxRecommendedWorkingSetSize` — approximately **4.4 GB** on iPhone 12 Pro (Metal's recommended working set). During model loading, transient computation buffers accumulate up to this cap. Combined with model weights, the process exceeds 2348 MB.

**Fix applied:** `Memory.cacheLimit = 0` before load, `Memory.clearCache()` after load, `Memory.cacheLimit = 20 MB` for inference.

### 2. VLM loads vision encoder (major contributor)
`VLMModelFactory.shared.loadContainer(...)` instantiates both the language model modules and the vision encoder modules. Even if image inputs are never used, the full weight file is loaded into wired (GPU-accessible) memory.

**Fix applied:** On devices with < 8 GB RAM, `detectRuntime` now returns `.gemma4LLM` instead of `.gemma4VLM`, routing to `LLMModelFactory` which only instantiates text backbone modules.

### 3. No `Increased Memory Limit` entitlement
Without `com.apple.developer.kernel.increased-memory-limit`, jetsam enforces a tighter baseline ceiling (~1.5–1.8 GB on older iOS; on iOS 26 appears already ~2.3 GB). The entitlement can raise this to ~50% of physical RAM.

**Fix applied:** Created `AnvitApp/AnvitApp.entitlements` and wired `CODE_SIGN_ENTITLEMENTS` in `project.pbxproj` for both Debug and Release configs.

---

## Memory Budget Analysis

| Component | Estimated Size |
|-----------|---------------|
| iOS + system processes | ~1.5–2.0 GB |
| App + Swift runtime overhead | ~200–300 MB |
| **Gemma 4 E2B language backbone (4-bit)** | **~1.0–1.5 GB** |
| **Vision encoder (float16)** | **~400–800 MB** |
| KV cache (at inference time) | ~200–400 MB |
| MLX transient workspace during load | ~200–400 MB spike |
| **Jetsam limit** | **2348 MB** |

Loading the VLM in full peaks at **~2.0–2.5 GB for the model alone**, which combined with app overhead, exceeds 2348 MB. Even loading text-only (~1.0–1.5 GB) is borderline and depends on whether transient spike fits.

---

## Fixes Applied So Far

### `iosApp/AnvitApp/MLXBridgeImpl.swift`
1. Added `import MLX`
2. Set `Memory.cacheLimit = 0` at the start of `load()` (before model weights are deserialized)
3. Force `.gemma4LLM` route for devices with < 8 GB RAM (skips vision encoder)
4. Call `Memory.clearCache()` after load completes
5. Restore `Memory.cacheLimit = 20 * 1024 * 1024` for inference

### `iosApp/AnvitApp/AnvitApp.entitlements` (new file)
```xml
<key>com.apple.developer.kernel.increased-memory-limit</key>
<true/>
```

### `iosApp/AnvitApp.xcodeproj/project.pbxproj`
Added `CODE_SIGN_ENTITLEMENTS = AnvitApp/AnvitApp.entitlements` to both Debug and Release `XCBuildConfiguration` blocks.

---

## Open Questions for Research

### Q1: Does LLMModelFactory skip vision encoder weights?
When loading a `gemma4` VLM safetensors file via `LLMModelFactory` (text-only path), does MLX:
- (a) load ALL tensors from the file into memory first, then discard vision keys? → peak still hits 3.3 GB
- (b) lazily/selectively load only the keys that match the text model architecture? → peak ~1.5 GB

This determines whether the text-only workaround actually saves memory during the load phase or only after.

**Where to look:** `mlx-swift/Source/MLX/` — safetensors loading implementation; specifically whether `loadArrays` is lazy (memory-mapped) or eager.

### Q2: What is the actual jetsam limit with `Increased Memory Limit` entitlement on iPhone 12 Pro / iOS 26?
- Normal app limit: appears to be ~2348 MB on this device/iOS version
- With entitlement: expected to be ~3 GB (50% of 6 GB physical RAM)
- Need to verify the entitlement is actually being applied (code signing + provisioning)

**How to verify:** After a crash with the entitlement in place, check the jetsam report — the `limit` field should be higher than 2348 MB.

### Q3: Does MLX support partial/selective tensor loading from safetensors?
If MLX supports memory-mapped or filtered loading (e.g., load only tensors matching a key prefix or set), we could load only the language backbone keys from the combined VLM safetensors file without ever mapping the vision encoder weights into RAM.

**Where to look:** `mlx-swift/Sources/Cmlx/mlx/io/load.cpp` or similar — safetensors reader implementation.

### Q4: Is there a text-only Gemma 4 E2B checkpoint available?
A separately published text-only checkpoint would not include vision encoder weights in the file, giving a true 1–1.5 GB footprint.

**Where to look:** HuggingFace `mlx-community` — search for `gemma-4-e2b-it-4bit` text-only variants. Model card on `google/gemma-4-e2b-it`.

### Q5: What memory limit do iOS demos of Gemma 4 E2B achieve?
Demos showing Gemma 4 E2B running on iPhone — what device were they on? iPhone 14 Pro / 15 Pro have 8 GB RAM (higher limit ~4 GB). iPhone 12 Pro at 6 GB with 2.3 GB limit may simply not be a supported target for this model.

---

## Relevant Source Files

| File | Purpose |
|------|---------|
| `iosApp/AnvitApp/MLXBridgeImpl.swift` | Swift C-bridge; model load, memory config, generation stream |
| `composeApp/src/iosArm64Main/kotlin/com/anvit/localai/inference/IosInferenceEngine.kt` | Kotlin side of C bridge |
| `composeApp/src/iosArm64Main/kotlin/com/anvit/localai/inference/IosInferenceService.kt` | iOS inference service (model loading orchestration) |
| `mlx-swift/Source/MLX/GPU+Metal.swift` | `Memory.cacheLimit`, `Memory.clearCache()`, `GPU.maxRecommendedWorkingSetBytes()` |
| `mlx-swift/Source/MLX/Documentation.docc/Articles/running-on-ios.md` | Official MLX iOS memory guidance |
| `mlx-swift-lm/Libraries/MLXLMCommon/WiredMemoryPolicies.swift` | WiredSumPolicy, WiredBudgetPolicy |
| `mlx-swift-lm/Libraries/MLXLMCommon/WiredMemoryUtils.swift` | `WiredMemoryUtils.tune()` — runtime measurement |
| `mlx-swift-lm/Libraries/MLXLMCommon/ChatSession.swift` | Session management; KV cache is `.empty` until first generation |

---

## MLX Memory API Reference

```swift
// Set max size of MLX's intermediate buffer cache (0 = no cache)
Memory.cacheLimit = 20 * 1024 * 1024   // 20 MB

// Free all currently cached (unused) buffers
Memory.clearCache()

// Total MLX allocation limit (defaults to 1.5× Metal's recommendedMaxWorkingSetSize)
Memory.memoryLimit = Int(...)

// Snapshot current active/cache/peak memory for diagnostics
let snap = Memory.snapshot()
print(snap)  // active, cache, peak

// Metal's recommended working set for this device
let maxBytes = GPU.maxRecommendedWorkingSetBytes()  // ~4.4 GB on iPhone 12 Pro
```

---

## Entitlement Reference

```
com.apple.developer.kernel.increased-memory-limit
```
- Available to all developers (no special approval required)
- Raises jetsam high-watermark from default (~1.5–2 GB) to ~50% of physical RAM
- Requires proper code signing; set via `CODE_SIGN_ENTITLEMENTS` in build settings
- Apple docs: https://developer.apple.com/documentation/bundleresources/entitlements/com_apple_developer_kernel_increased-memory-limit
