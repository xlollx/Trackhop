package com.xlollx.songport.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xlollx.songport.R
import java.io.IOException
import java.io.OutputStream

/**
 * A file the person saves where the system's picker said (an export, a backup, a log): written
 * whole, or said as not saved. The picker has already made the empty file: when it cannot be opened,
 * or the write stops half way (no space left, the card removed, the cloud folder gone), what is
 * there is removed and the failure reaches the screen in plain words. Before, a file that could not
 * be opened was reported as exported, and half a playlist stayed behind looking like a whole one.
 */
object DocumentWrite {
    /** The write did not finish; [message] is for the person. */
    class NotSaved(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * [write] into what [open] gives; on any failure [discard] takes the half-written file away and
     * [NotSaved] is thrown with [message]. (Kept free of Android, for the unit tests.)
     */
    fun whole(open: () -> OutputStream?, discard: () -> Unit, message: String, write: (OutputStream) -> Unit) {
        try {
            val out = open() ?: throw IOException("no stream")
            out.use { write(it); it.flush() }
        } catch (e: Exception) {
            runCatching(discard)
            throw NotSaved(message, e)
        }
    }

    fun bytes(ctx: Context, uri: Uri, bytes: ByteArray) = to(ctx, uri) { it.write(bytes) }

    fun text(ctx: Context, uri: Uri, text: String) = bytes(ctx, uri, text.toByteArray(Charsets.UTF_8))

    fun to(ctx: Context, uri: Uri, write: (OutputStream) -> Unit) = whole(
        open = { ctx.contentResolver.openOutputStream(uri) },
        discard = { DocumentsContract.deleteDocument(ctx.contentResolver, uri) },
        message = ctx.getString(R.string.export_not_saved),
        write = write,
    )
}
