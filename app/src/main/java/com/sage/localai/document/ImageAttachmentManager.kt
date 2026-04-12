package com.sage.localai.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Manages image attachments for chat messages.
 *
 * Images are copied from content URIs into stable internal storage
 * (filesDir/chat_images/) so they can survive the picker lifecycle and be
 * recalled when the chat history is re-rendered.
 */
object ImageAttachmentManager {

    private const val TAG = "ImageAttachment"
    private const val DIR = "chat_images"
    private const val MAX_DIM = 1024   // max width or height after scaling
    private const val QUALITY = 85     // JPEG quality

    /**
     * Copy an image from a content URI into stable internal storage.
     * @return the absolute file path of the saved image.
     */
    fun copyToStorage(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, DIR).also { it.mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.jpg")

        context.contentResolver.openInputStream(uri)?.use { input ->
            // Decode → scale → re-encode as JPEG to normalise format and cap size
            val original = BitmapFactory.decodeStream(input)
                ?: throw IllegalArgumentException("Cannot decode image at $uri")
            val scaled = scaleBitmap(original)
            FileOutputStream(dest).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            if (scaled !== original) original.recycle()
            Log.d(TAG, "Image saved to ${dest.absolutePath}")
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        return dest.absolutePath
    }

    /**
     * Load an already-saved image from internal storage, scaling it to fit within MAX_DIM.
     */
    fun loadScaledBitmap(path: String): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)

        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, MAX_DIM, MAX_DIM)
        opts.inJustDecodeBounds = false

        return BitmapFactory.decodeFile(path, opts)
            ?: throw IllegalStateException("Cannot decode image at $path")
    }

    /**
     * Encode a Bitmap as a JPEG byte array (used for the base64-in-prompt fallback
     * and for the SDK's InlineData API once Content.Image becomes available).
     */
    fun toJpegBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return out.toByteArray()
    }

    /** Delete a stored image file. Call after the parent chat message is deleted. */
    fun delete(path: String) {
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image at $path: ${e.message}")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_DIM && h <= MAX_DIM) return bitmap
        val scale = MAX_DIM.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
