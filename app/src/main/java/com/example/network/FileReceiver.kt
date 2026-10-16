package com.example.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves files the computer sends to this phone. They go to Downloads/ZoharaLink (Android 10 and newer, through MediaStore, so
 * no storage permission is needed) or to the app's own Downloads folder on older Android. A file is hidden from other apps
 * until it is complete, and a cut-off one is deleted.
 */
class FileReceiver(private val context: Context) {
    private class Open(val saveAs: String, val declared: Long, val out: OutputStream, val uri: Uri?, val file: File?) {
        var written = 0L
    }

    private val open = HashMap<String, Open>()

    /** The name with any folder part and odd characters removed. */
    fun cleanName(name: String): String {
        val last = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '"' }.trim().trim('.')
        return last.take(120).ifEmpty { "zohara-file" }
    }

    /** Starts saving [name]; false when the file cannot be created. */
    @Synchronized
    fun begin(id: String, name: String, size: Long): Boolean {
        val clean = cleanName(name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ext = clean.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, clean)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZoharaLink")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                val out = context.contentResolver.openOutputStream(uri) ?: return false
                open[id] = Open(clean, size, out, uri, null)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
                dir.mkdirs()
                var target = File(dir, clean)
                var n = 1
                while (target.exists()) {
                    target = File(dir, clean.substringBeforeLast('.', clean) + " ($n)" + (if (clean.contains('.')) "." + clean.substringAfterLast('.') else ""))
                    n++
                }
                open[id] = Open(target.name, size, FileOutputStream(target), null, target)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Adds bytes to a file; false when it cannot be written or the computer sent more than it announced (the caller then calls [abort]). */
    @Synchronized
    fun write(id: String, bytes: ByteArray): Boolean {
        val o = open[id] ?: return false
        if (o.declared > 0 && o.written + bytes.size > o.declared) return false
        return try {
            o.out.write(bytes)
            o.written += bytes.size
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Percent written so far (0 when the size was not announced). */
    @Synchronized
    fun percent(id: String): Int {
        val o = open[id] ?: return 0
        return if (o.declared > 0) (o.written * 100 / o.declared).toInt().coerceIn(0, 100) else 0
    }

    /** Ends a file: it becomes visible to other apps. Returns the name it was saved as, or null when it was not complete. */
    @Synchronized
    fun finish(id: String): String? {
        val o = open.remove(id) ?: return null
        return try {
            o.out.flush()
            o.out.close()
            if (o.declared > 0 && o.written != o.declared) {
                discard(o)
                return null
            }
            if (o.uri != null) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(o.uri, values, null, null)
            }
            o.saveAs
        } catch (e: Exception) {
            discard(o)
            null
        }
    }

    /** Throws away a half-written file. */
    @Synchronized
    fun abort(id: String) {
        open.remove(id)?.let { discard(it) }
    }

    @Synchronized
    fun abortAll() {
        open.keys.toList().forEach { abort(it) }
    }

    private fun discard(o: Open) {
        try { o.out.close() } catch (_: Exception) {}
        try {
            if (o.uri != null) context.contentResolver.delete(o.uri, null, null) else o.file?.delete()
        } catch (_: Exception) {}
    }
}
