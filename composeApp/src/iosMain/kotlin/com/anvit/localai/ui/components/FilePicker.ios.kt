package com.anvit.localai.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPdfPicker(onResult: (fileName: String, bytes: ByteArray) -> Unit): () -> Unit {
    // v2: replace with UIDocumentPickerViewController via ComposeUIViewController interop
    return { println("IosPdfPicker: PDF file picking not yet implemented on iOS (v1 stub)") }
}
