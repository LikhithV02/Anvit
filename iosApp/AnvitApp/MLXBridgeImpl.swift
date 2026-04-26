// MLXBridgeImpl.swift
// Exports C-callable symbols (via @_cdecl) that implement the mlxbridge.h API.
// This file must be compiled as part of the AnvitApp Xcode target.
//
// Required SPM packages in the Xcode project:
//   1. mlx-swift-lm (local: ../mlx-swift-lm) — provides MLXLLM, MLXVLM, MLXLMCommon, MLXHuggingFace
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
        // Try VLM factory first (covers Gemma4 VLM); falls through to LLM factory for text models.
        let c = try await loadModelContainer(from: directory, using: tokenizerLoader)
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

        // Create the async stream while still actor-isolated (safe access to sess).
        let stream = sess.streamResponse(to: prompt, images: images, videos: [])
        // stream is AsyncThrowingStream<String, Error> which is Sendable — safe to capture.

        genTask = Task { @Sendable [tokenCallback] in
            do {
                for try await chunk in stream {
                    guard !Task.isCancelled else { break }
                    tokenCallback(chunk, false, nil)
                }
                if !Task.isCancelled {
                    tokenCallback(nil, true, nil)
                }
            } catch {
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
            if let token {
                token.withCString  { tokenCallback(userData, $0, isFinal, nil) }
            } else if let errMsg {
                errMsg.withCString { tokenCallback(userData, nil, isFinal, $0) }
            } else {
                tokenCallback(userData, nil, isFinal, nil)
            }
        }
    }
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
