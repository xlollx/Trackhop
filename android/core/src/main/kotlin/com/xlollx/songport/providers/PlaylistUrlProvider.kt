package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * A playlist file someone keeps online (2.2): a link to an .xspf, .m3u/.m3u8, .jspf or .pls file,
 * pasted as the source of a sync and read again at every run, as a File playlist is read from disk.
 * Nothing of a service is involved (the file is fetched as any page is), so it is in every build.
 */
object PlaylistUrlProvider : LinkSource() {
    const val SERVICE = "playlisturl"
    override val serviceId = SERVICE
    override val displayName = "Playlist file (link)"
    override val brandColor = 0xFF607D8B
    override val noteName = "provider_note_playlisturl"
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override val pluginBased: Boolean get() = false
    override val searchParallelism = 4
    override fun canRead(ctx: Env, playlistId: String) = isLink(playlistId)

    /** The most of a file read: a playlist far bigger than this is not one. */
    private const val MAX_BYTES = 20L * 1024 * 1024

    /** Only web addresses: a link names a file online, never one on this device or server. */
    fun isLink(id: String): Boolean = id.startsWith("https://", ignoreCase = true) || id.startsWith("http://", ignoreCase = true)

    /** "https://x.org/lists/Road%20trip.xspf?v=2" is "Road trip.xspf". */
    fun fileName(url: String): String =
        runCatching { java.net.URLDecoder.decode(url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8") }
            .getOrDefault("").ifBlank { "playlist" }

    private suspend fun fetch(ctx: Env, url: String): String {
        if (!isLink(url)) throw ProviderException(ctx.text("playlisturl_unreadable", url))
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", "Trackhop").header("Accept", "*/*").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ProviderException(ctx.text("playlisturl_unreadable", "HTTP ${r.code}"), code = r.code)
                val body = r.body ?: throw ProviderException(ctx.text("playlisturl_unreadable", "empty"))
                if (body.contentLength() > MAX_BYTES) throw ProviderException(ctx.text("playlisturl_unreadable", "too big"))
                val out = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw ProviderException(ctx.text("playlisturl_unreadable", "too big"))
                    }
                }
                PlaylistFiles.decode(out.toByteArray())
            }
        }
    }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val text = fetch(ctx, playlistId)
        val tracks = try {
            PlaylistFiles.parse(fileName(playlistId), text)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            throw ProviderException(ctx.text("playlisturl_unreadable", e.message ?: e.javaClass.simpleName), e)
        }
        return tracks.map { if (it.id.isBlank()) CsvCodec.withStableId(it) else it }
    }

    /** The file's own title where it has one (XSPF, JSPF), else its name without the extension. */
    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist {
        val name = fileName(playlistId).substringBeforeLast('.').ifBlank { fileName(playlistId) }
        return Playlist(playlistId, name, ownedByMe = false)
    }
}
