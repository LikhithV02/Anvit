package com.anvit.localai.ui.components

import androidx.compose.runtime.Composable

/**
 * Returns a lambda that, when invoked, opens the platform's document picker
 * and delivers the selected PDF as (fileName, ByteArray) via [onResult].
 *
 * androidMain: uses ActivityResultContracts.OpenDocument + ContentResolver
 * iosMain:     stub for v1 (PDFKit picker deferred to v2)
 */
@Composable
expect fun rememberPdfPicker(onResult: (fileName: String, bytes: ByteArray) -> Unit): () -> Unit
