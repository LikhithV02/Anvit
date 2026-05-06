package com.anvit.localai.document

internal expect fun inflateRaw(compressed: ByteArray, uncompressedSize: Int): ByteArray?
