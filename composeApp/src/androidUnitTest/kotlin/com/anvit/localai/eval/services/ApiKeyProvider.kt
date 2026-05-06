package com.anvit.localai.eval.services

object ApiKeyProvider {
    fun geminiApiKey(required: Boolean = true): String? {
        val key = System.getenv("GEMINI_API_KEY")
            ?: System.getProperty("GEMINI_API_KEY")
            ?: System.getProperty("gemini.api.key")
        if (required && key.isNullOrBlank()) {
            error("GEMINI_API_KEY is required for the Anvit eval suite.")
        }
        return key?.takeIf { it.isNotBlank() }
    }
}
