package com.anvit.localai.document

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRaw(compressed: ByteArray, uncompressedSize: Int): ByteArray? {
    if (uncompressedSize <= 0) return null
    val output = ByteArray(uncompressedSize)
    var ok = false
    compressed.usePinned { inPin ->
        output.usePinned { outPin ->
            val stream = nativeHeap.alloc<z_stream>()
            stream.next_in   = inPin.addressOf(0).reinterpret()
            stream.avail_in  = compressed.size.toUInt()
            stream.next_out  = outPin.addressOf(0).reinterpret()
            stream.avail_out = uncompressedSize.toUInt()
            if (inflateInit2(stream.ptr, -15) == Z_OK) {
                val rc = inflate(stream.ptr, Z_FINISH)
                inflateEnd(stream.ptr)
                ok = rc == Z_STREAM_END || rc == Z_OK
            }
            nativeHeap.free(stream)
        }
    }
    return if (ok) output else null
}
