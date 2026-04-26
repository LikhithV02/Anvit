#ifndef MLXBRIDGE_H
#define MLXBRIDGE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Callbacks carry a user_data pointer so callers can associate context
 * (e.g. a Kotlin/Native StableRef) with each in-flight call.
 */
typedef void (*MLXLoadCallback)(void* user_data, bool success, const char* error_message);
typedef void (*MLXTokenCallback)(void* user_data, const char* token, bool is_final, const char* error_message);

/** Load a model from a local directory path. Calls callback on the Swift concurrency thread pool. */
void mlx_engine_load(const char* model_dir_path, void* user_data, MLXLoadCallback callback);

/**
 * Stream generation. token_callback fires for each chunk; is_final=true on the last call.
 * image_path and system_prompt may be NULL.
 */
void mlx_engine_generate_stream(
    const char* prompt,
    const char* system_prompt,
    const char* image_path,
    float temperature,
    int top_k,
    float top_p,
    int max_tokens,
    void* user_data,
    MLXTokenCallback token_callback
);

/** Cancel in-flight generation. No-op if nothing is running. */
void mlx_engine_cancel(void);

/** Unload model and release all MLX resources. */
void mlx_engine_unload(void);

/** Synchronously returns whether a model is loaded. */
bool mlx_engine_is_loaded(void);

/** Clear the ChatSession KV cache (call on chat reset). */
void mlx_engine_reset_session(void);

#ifdef __cplusplus
}
#endif

#endif /* MLXBRIDGE_H */
