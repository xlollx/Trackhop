package com.xlollx.songport.providers

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.GameSongFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A rhythm game's song folder on this computer as a source (Desktop and Docker): Clone Hero, YARG
 * and Phase Shift (`song.ini`), StepMania, ITGmania and OutFox (`.sm`/`.ssc`), Beat Saber's custom
 * levels (`Info.dat`). All its songs, and each pack or setlist folder, become playlists to copy to a
 * music service ([GameSongFiles]). Read-only: only the songs' text files are read, nothing is written
 * to the game's folders. Offered by the server only, where the folder is a path it can read.
 */
class GameFolderProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Rhythm game songs"
    override val brandColor = 0xFF2E7D32
    override val noteName = "provider_note_gamefolder"
    override val beta = true
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val category get() = ServiceCatalog.Category.GAMES
    override val aliases get() = listOf("Clone Hero", "YARG", "Phase Shift", "StepMania", "ITGmania", "OutFox", "Beat Saber")
    override val loginForm = LoginForm(needsUrl = true, needsUser = false, needsSecret = false, hintName = "login_hint_gamefolder", urlIsFolder = true)

    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.ifBlank { null }

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        val dir = File(url.trim()).takeIf { url.isNotBlank() }?.canonicalFile
        if (dir == null || !dir.isDirectory) throw ProviderException(ctx.text("gamefolder_missing", url.trim()))
        val songs = withContext(Dispatchers.IO) { GameSongFiles.scan(dir) }
        if (songs.isEmpty()) throw ProviderException(ctx.text("gamefolder_empty"))
        scans[dir.path] = System.currentTimeMillis() to songs
        save(ctx, Tokens(accessToken = dir.path, userName = dir.name.ifBlank { dir.path }, extra = mapOf("url" to dir.path)))
    }

    private suspend fun songs(ctx: Env): List<GameSongFiles.Song> {
        val path = creds(ctx).accessToken
        scans[path]?.let { (at, list) -> if (System.currentTimeMillis() - at < FRESH_MS) return list }
        val dir = File(path)
        if (!dir.isDirectory) throw ProviderException(ctx.text("gamefolder_missing", path))
        val list = withContext(Dispatchers.IO) { GameSongFiles.scan(dir) }
        scans[path] = System.currentTimeMillis() to list
        return list
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val all = songs(ctx)
        return listOf(Playlist(ALL, ctx.text("gamefolder_all"), all.size)) +
            all.filter { it.pack.isNotEmpty() }.groupBy { it.pack }.map { (pack, list) -> Playlist(PACK + pack, pack, list.size) }
    }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        if (playlistId == ALL) Playlist(ALL, ctx.text("gamefolder_all")) else Playlist(playlistId, playlistId.removePrefix(PACK))

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        val all = songs(ctx)
        val chosen = if (playlistId == ALL) all else all.filter { PACK + it.pack == playlistId }
        return chosen.map { it.track() }
    }

    /** The songs and the newest change among the folders: a scheduled sync skips the run when nothing changed. */
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = runCatching {
        val dir = File(creds(ctx).accessToken)
        val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        "${dir.lastModified()}:$newest"
    }.getOrNull()

    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object {
        const val SERVICE = "gamefolder"
        const val ALL = "*"
        const val PACK = "pack:"
        private const val FRESH_MS = 10 * 60_000L
        private val scans = ConcurrentHashMap<String, Pair<Long, List<GameSongFiles.Song>>>()
    }
}
