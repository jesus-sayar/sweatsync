package com.sweatsync.util

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

object Downloads {

    /** Saves [bytes] in the public Downloads folder. Returns false when it could not be written. */
    fun save(context: Context, fileName: String, bytes: ByteArray): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        } catch (e: java.io.IOException) {
            false
        }
    }
}
