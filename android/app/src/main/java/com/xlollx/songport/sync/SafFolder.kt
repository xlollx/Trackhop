package com.xlollx.songport.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.xlollx.songport.shared.DirFolder
import com.xlollx.songport.shared.GroupFolder
import com.xlollx.songport.shared.SharedFolders

/**
 * A shared playlist's folder picked with the system's folder picker (the "File" tools' and the
 * backup folder's way): Google Drive, Nextcloud, Dropbox, or a Syncthing folder on the phone.
 * A file is replaced whole where the folder's app allows it (written beside, then renamed over);
 * elsewhere it is written in place, and the other members skip a copy they catch half written.
 */
class SafFolder(ctx: Context, private val tree: Uri) : GroupFolder {
    private val resolver = ctx.contentResolver
    private val dirId = DocumentsContract.getTreeDocumentId(tree)
    private val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)

    private class Entry(val name: String, val uri: Uri, val size: Long, val flags: Int)

    private fun children(): List<Entry> {
        val out = ArrayList<Entry>()
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(4) == Document.MIME_TYPE_DIR) continue
                val name = c.getString(1) ?: continue
                out += Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), if (c.isNull(2)) -1 else c.getLong(2), c.getInt(3))
            }
        } ?: throw java.io.IOException("folder not readable")
        return out
    }

    override fun list(): List<String> = children().map { it.name }

    override fun read(name: String, max: Int): ByteArray? {
        val e = children().firstOrNull { it.name == name } ?: return null
        if (e.size > max) return null
        return runCatching {
            resolver.openInputStream(e.uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return@use null
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    override fun write(name: String, bytes: ByteArray) {
        val all = children()
        val tmpName = ".$name.tmp"
        all.filter { it.name == tmpName }.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }
        val old = all.firstOrNull { it.name == name }
        // Beside, then over: readers never meet a half-written file.
        val tmp = runCatching { DocumentsContract.createDocument(resolver, dirUri, MIME, tmpName) }.getOrNull()
        if (tmp != null) {
            val written = runCatching { put(tmp, bytes) }.isSuccess
            val flags = children().firstOrNull { it.name == tmpName }?.flags ?: 0
            val renamable = written && (flags and Document.FLAG_SUPPORTS_RENAME) != 0
            if (renamable && (old == null || runCatching { DocumentsContract.deleteDocument(resolver, old.uri) }.getOrDefault(false))) {
                val renamed = runCatching { DocumentsContract.renameDocument(resolver, tmp, name) }.isSuccess &&
                    children().any { it.name == name }
                if (renamed) return
            }
            runCatching { DocumentsContract.deleteDocument(resolver, tmp) }
        }
        // In place: the folder's app cannot rename (or the rename went wrong).
        val target = children().firstOrNull { it.name == name }?.uri
            ?: DocumentsContract.createDocument(resolver, dirUri, MIME, name)
            ?: throw java.io.IOException("cannot create $name")
        put(target, bytes)
    }

    private fun put(doc: Uri, bytes: ByteArray) {
        resolver.openOutputStream(doc, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("cannot write")
    }

    companion object {
        private const val MIME = "application/json"

        /** Shared playlists open their folders through this: a picked folder by its address, a path as a plain folder. */
        fun install(ctx: Context) {
            val app = ctx.applicationContext
            SharedFolders.opener = { ref -> if (ref.startsWith("content://")) SafFolder(app, Uri.parse(ref)) else DirFolder(java.io.File(ref)) }
        }

        /** The folder's name as the picker shows it, for the group's screen. */
        fun displayName(ctx: Context, tree: Uri): String = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty()
    }
}
