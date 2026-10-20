package com.stegpony.android.io

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A file the user picked or shared in, before its bytes are read. */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long?,
    val mime: String?,
)

class TooLargeException(val limit: Long) : IOException("larger than $limit bytes")

/**
 * File access for the flows. Images always travel as their exact bytes: the
 * core reads and writes files, and nothing here decodes or re-encodes one.
 */
object Files {
    /** Largest cover or stego image read in. */
    const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

    /** Largest file payload read in; the cover's capacity is far below this. */
    const val MAX_PAYLOAD_BYTES = 16L * 1024 * 1024

    fun describe(context: Context, uri: Uri): PickedFile {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        return PickedFile(
            uri = uri,
            name = safeName(name ?: uri.lastPathSegment ?: "file"),
            size = size,
            mime = context.contentResolver.getType(uri),
        )
    }

    /** Reads the whole file, refusing anything over [limit] bytes. */
    fun readBytes(context: Context, uri: Uri, limit: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("could not open $uri")
        return input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException(limit)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    /** Writes [bytes] to a document the user chose, replacing its contents. */
    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("could not open $uri for writing")
        out.use { it.write(bytes) }
    }

    /**
     * A share intent for [bytes] under [name]. The file sits in the app's cache
     * (shared/[area], served by the FileProvider) until the next share from the
     * same area replaces it. Does file I/O; call off the main thread.
     */
    fun shareIntent(context: Context, area: String, bytes: ByteArray, name: String, mime: String): Intent {
        val dir = File(File(context.cacheDir, "shared"), area)
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, safeName(name))
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /** Removes what was shared from [area], or from everywhere when null. */
    fun clearShared(context: Context, area: String? = null) {
        val root = File(context.cacheDir, "shared")
        (if (area == null) root else File(root, area)).deleteRecursively()
    }

    const val AREA_HIDE = "hide"
    const val AREA_REVEAL = "reveal"

    /**
     * A file name that cannot leave its directory: the last path segment only,
     * no control characters, never empty or a dot name.
     */
    fun safeName(raw: String): String {
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = last.filter { it >= ' ' && it != '\u007f' && it != ':' }.trim()
        return when {
            cleaned.isEmpty() || cleaned == "." || cleaned == ".." -> "file"
            cleaned.length > 120 -> cleaned.take(120)
            else -> cleaned
        }
    }

    /** A camera-style name for a hidden-in image, so the name gives nothing away. */
    fun outputName(extension: String, now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        return "IMG_$stamp.$extension"
    }

    /** A plain name for saved text, like a note app would give it. */
    fun textOutputName(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        return "Note_$stamp.txt"
    }
}
