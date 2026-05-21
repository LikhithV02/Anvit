// MLXBridgeImpl.swift
// Exports C-callable symbols (via @_cdecl) that implement the mlxbridge.h API.
// This file must be compiled as part of the AnvitApp Xcode target.
//
// Required SPM packages in the Xcode project:
//   1. mlx-swift-lm — provides MLXLLM, MLXVLM, MLXLMCommon, MLXHuggingFace
//   2. swift-transformers (https://github.com/huggingface/swift-transformers) — provides Tokenizers
//
// Required framework targets linked to AnvitApp:
//   MLXLLM, MLXVLM, MLXLMCommon, MLXHuggingFace, Tokenizers

import Foundation
import CoreImage
import MLXLLM
import MLXVLM
import MLXLMCommon
import MLXHuggingFace
import Tokenizers

// MARK: - Engine actor

/// Serialises all model state access on a single actor.
/// ChatSession is not Sendable; wrapping it in an actor ensures exclusive access.
private actor MLXBridgeEngine {

    /// Synchronous flag readable without actor hop (safe: written only inside the actor).
    nonisolated(unsafe) var isLoaded = false

    private var container: ModelContainer?
    private var session: ChatSession?
    private var genTask: Task<Void, Never>?

    func load(from directory: URL, tokenizerLoader: any TokenizerLoader) async throws {
        genTask?.cancel()
        genTask = nil
        session = nil
        container = nil
        isLoaded = false
        let runtime = try detectRuntime(for: directory)
        // Local directory loading bypasses mlx-swift-lm's named model registry, so
        // reapply model-specific stop tokens here.
        let configuration = ModelConfiguration(
            directory: directory,
            extraEOSTokens: runtime.extraEOSTokens
        )
        let c: ModelContainer
        switch runtime {
        case .gemma4VLM:
            c = try await VLMModelFactory.shared.loadContainer(
                from: LocalOnlyDownloader(),
                using: tokenizerLoader,
                configuration: configuration
            )
            print("Anvit MLX: loaded Gemma 4 VLM container for \(directory.lastPathComponent)")
        case .gemma4LLM:
            c = try await LLMModelFactory.shared.loadContainer(
                from: LocalOnlyDownloader(),
                using: tokenizerLoader,
                configuration: configuration
            )
            print("Anvit MLX: loaded Gemma 4 LLM container for \(directory.lastPathComponent)")
        case .llm:
            c = try await LLMModelFactory.shared.loadContainer(
                from: LocalOnlyDownloader(),
                using: tokenizerLoader,
                configuration: configuration
            )
            print("Anvit MLX: loaded LLM container for \(directory.lastPathComponent)")
        }
        let s = ChatSession(c)
        container = c
        session = s
        isLoaded = true
    }

    func unload() {
        genTask?.cancel()
        genTask = nil
        session = nil
        container = nil
        isLoaded = false
    }

    func resetSession() {
        guard let c = container else { return }
        session = ChatSession(c)
    }

    func generate(
        prompt: String,
        systemPrompt: String?,
        imagePath: String?,
        params: GenerateParameters,
        tokenCallback: @escaping @Sendable (String?, Bool, String?) -> Void
    ) {
        genTask?.cancel()
        guard let sess = session else {
            tokenCallback(nil, true, "MLX model not loaded")
            return
        }

        sess.instructions = systemPrompt
        sess.generateParameters = params

        // Build image list before spawning the Task.
        // .url case avoids loading UIImage into memory here; mlx-swift-lm handles decoding.
        let images: [UserInput.Image] = imagePath.map { [.url(URL(fileURLWithPath: $0))] } ?? []

        let promptCharCount = prompt.count
        let systemCharCount = systemPrompt?.count ?? 0
        let requestedMaxTokens = params.maxTokens ?? -1
        print("Anvit MLX: generation requested promptChars=\(promptCharCount) systemChars=\(systemCharCount) maxTokens=\(requestedMaxTokens) image=\(imagePath != nil)")

        // Create the async stream while still actor-isolated (safe access to sess).
        let stream = sess.streamDetails(to: prompt, images: images, videos: [])
        // stream is AsyncThrowingStream<Generation, Error> which is Sendable — safe to capture.

        genTask = Task { @Sendable [tokenCallback] in
            let startedAt = Date()
            var firstChunkLogged = false
            var emittedChunks = 0
            var emittedCharacters = 0
            var invisibleChunkStreak = 0
            do {
                for try await item in stream {
                    guard !Task.isCancelled else { break }
                    switch item {
                    case .chunk(let chunk):
                        if !firstChunkLogged {
                            firstChunkLogged = true
                            print("Anvit MLX: first text chunk after \(elapsedSeconds(since: startedAt))s preview=\"\(chunkPreview(chunk))\"")
                        }
                        emittedChunks += 1
                        emittedCharacters += chunk.count
                        if emittedChunks <= 8 {
                            print("Anvit MLX: chunk[\(emittedChunks)] chars=\(chunk.count) preview=\"\(chunkPreview(chunk))\"")
                        }
                        if hasVisibleContent(chunk) {
                            invisibleChunkStreak = 0
                        } else {
                            invisibleChunkStreak += 1
                            if invisibleChunkStreak == 16 {
                                print("Anvit MLX: still receiving invisible chunks; latest preview=\"\(chunkPreview(chunk))\"")
                            }
                            if invisibleChunkStreak >= 64 {
                                print("Anvit MLX: stopping after 64 consecutive invisible chunks")
                                break
                            }
                        }
                        tokenCallback(chunk, false, nil)
                    case .info(let info):
                        let promptTime = formatSeconds(info.promptTime)
                        let generationTime = formatSeconds(info.generateTime)
                        let summary = "Anvit MLX: completed promptTokens=\(info.promptTokenCount) generatedTokens=\(info.generationTokenCount) promptTime=\(promptTime)s generationTime=\(generationTime)s stopReason=\(info.stopReason)"
                        print(summary)
                    case .toolCall:
                        break
                    }
                }
                if !Task.isCancelled {
                    print("Anvit MLX: stream finished elapsed=\(elapsedSeconds(since: startedAt))s chunks=\(emittedChunks) chars=\(emittedCharacters)")
                    tokenCallback(nil, true, nil)
                }
            } catch {
                print("Anvit MLX: generation failed after \(elapsedSeconds(since: startedAt))s: \(error.localizedDescription)")
                tokenCallback(nil, true, error.localizedDescription)
            }
        }
    }

    func cancel() {
        genTask?.cancel()
    }
}

// MARK: - Singleton state

private let _engine = MLXBridgeEngine()

private struct LocalOnlyDownloader: Downloader {
    func download(
        id: String,
        revision: String?,
        matching patterns: [String],
        useLatest: Bool,
        progressHandler: @Sendable @escaping (Progress) -> Void
    ) async throws -> URL {
        throw NSError(
            domain: "Anvit.MLXBridge",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Unexpected remote download request for local MLX model: \(id)"]
        )
    }
}

private enum MLXRuntimeKind {
    case gemma4VLM
    case gemma4LLM
    case llm

    var extraEOSTokens: Set<String> {
        switch self {
        case .gemma4VLM:
            return ["<end_of_turn>"]
        case .gemma4LLM:
            return ["<turn|>"]
        case .llm:
            return []
        }
    }
}

private func detectRuntime(for directory: URL) throws -> MLXRuntimeKind {
    let config = try loadJSONFile(directory.appendingPathComponent("config.json"))
    let processor = try? loadJSONFile(directory.appendingPathComponent("processor_config.json"))
    let modelType = stringValue(config["model_type"])?.lowercased()
    let processorClass = stringValue(processor?["processor_class"])

    if modelType == "gemma4" {
        if config["vision_config"] != nil || processorClass == "Gemma4Processor" {
            return .gemma4VLM
        }
        return .gemma4LLM
    }

    if modelType == "gemma4_text" {
        return .gemma4LLM
    }

    return .llm
}

private func loadJSONFile(_ url: URL) throws -> [String: Any] {
    let data = try Data(contentsOf: url)
    let object = try JSONSerialization.jsonObject(with: data)
    return object as? [String: Any] ?? [:]
}

private func stringValue(_ value: Any?) -> String? {
    value as? String
}

private let _tokenizerLoader: any TokenizerLoader = {
    // Expands to a struct that wraps Tokenizers.AutoTokenizer (from swift-transformers).
    #huggingFaceTokenizerLoader()
}()

// MARK: - C-exported bridge functions

/// Load an MLX model from a local directory path.
/// `callback(user_data, success, error_message)` fires on task completion.
@_cdecl("mlx_engine_load")
public func mlxEngineLoad(
    _ path: UnsafePointer<CChar>,
    _ userData: UnsafeMutableRawPointer?,
    _ callback: @convention(c) @escaping (UnsafeMutableRawPointer?, Bool, UnsafePointer<CChar>?) -> Void
) {
    let modelPath = String(cString: path)
    Task {
        do {
            let url = URL(fileURLWithPath: modelPath, isDirectory: true)
            try await _engine.load(from: url, tokenizerLoader: _tokenizerLoader)
            callback(userData, true, nil)
        } catch {
            let msg = error.localizedDescription
            msg.withCString { callback(userData, false, $0) }
        }
    }
}

/// Stream text (and optionally image) generation.
/// `token_callback(user_data, token, is_final, error_message)` fires per chunk.
@_cdecl("mlx_engine_generate_stream")
public func mlxEngineGenerateStream(
    _ prompt: UnsafePointer<CChar>,
    _ systemPrompt: UnsafePointer<CChar>?,
    _ imagePath: UnsafePointer<CChar>?,
    _ temperature: Float,
    _ topK: Int32,
    _ topP: Float,
    _ maxTokens: Int32,
    _ userData: UnsafeMutableRawPointer?,
    _ tokenCallback: @convention(c) @escaping (UnsafeMutableRawPointer?, UnsafePointer<CChar>?, Bool, UnsafePointer<CChar>?) -> Void
) {
    let promptStr    = String(cString: prompt)
    let sysStr       = systemPrompt.map { String(cString: $0) }
    let imgStr       = imagePath.map   { String(cString: $0) }
    let params       = GenerateParameters(
        maxTokens:   Int(maxTokens),
        temperature: temperature,
        topP:        topP,
        topK:        Int(topK)
    )

    Task {
        await _engine.generate(
            prompt:        promptStr,
            systemPrompt:  sysStr,
            imagePath:     imgStr,
            params:        params
        ) { @Sendable token, isFinal, errMsg in
            if let token, !isPaddingOnly(token) {
                token.withCString { tokenCallback(userData, $0, isFinal, nil) }
            } else if let errMsg {
                errMsg.withCString { tokenCallback(userData, nil, isFinal, $0) }
            } else {
                tokenCallback(userData, nil, isFinal, nil)
            }
        }
    }
}

private func isPaddingOnly(_ token: String) -> Bool {
    let stripped = token
        .replacingOccurrences(of: "<pad>", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return stripped.isEmpty && token.contains("<pad>")
}

private func hasVisibleContent(_ token: String) -> Bool {
    let stripped = token
        .replacingOccurrences(of: "<pad>", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return !stripped.isEmpty
}

private func chunkPreview(_ token: String) -> String {
    let escaped = token
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\n", with: "\\n")
        .replacingOccurrences(of: "\r", with: "\\r")
        .replacingOccurrences(of: "\t", with: "\\t")
    let prefix = escaped.prefix(80)
    return escaped.count > 80 ? "\(prefix)..." : String(prefix)
}

private func elapsedSeconds(since start: Date) -> String {
    formatSeconds(Date().timeIntervalSince(start))
}

private func formatSeconds(_ value: TimeInterval) -> String {
    String(format: "%.2f", value)
}

/// Cancel in-flight generation.
@_cdecl("mlx_engine_cancel")
public func mlxEngineCancel() {
    Task { await _engine.cancel() }
}

/// Unload the model and free all MLX resources.
@_cdecl("mlx_engine_unload")
public func mlxEngineUnload() {
    Task { await _engine.unload() }
}

/// Synchronously returns whether a model is currently loaded.
@_cdecl("mlx_engine_is_loaded")
public func mlxEngineIsLoaded() -> Bool {
    _engine.isLoaded
}

/// Clear the ChatSession KV cache (call when the user starts a new chat).
@_cdecl("mlx_engine_reset_session")
public func mlxEngineResetSession() {
    Task { await _engine.resetSession() }
}
