package com.xlollx.songport.flows

import com.xlollx.songport.Demo
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Matcher
import java.util.concurrent.ConcurrentHashMap

/**
 * The music services of the UI flow tests: no network, no account, all state in memory and
 * reset before each test. Each fake answers under a real service id, so the real logos, link
 * parsing, share sheet and editor logic apply; what a sync writes lands in [FakeWorld] and the
 * tests read it back from there.
 */
object FakeWorld {
    class FakePlaylist(val id: String, var name: String, val tracks: MutableList<Track>, val owned: Boolean = true)

    /** Provider id -> its playlists, in order. */
    val playlists = ConcurrentHashMap<String, MutableList<FakePlaylist>>()
    /** Public playlists reachable by link (not in anyone's library): provider id -> (playlist id -> playlist). */
    val public = ConcurrentHashMap<String, MutableMap<String, FakePlaylist>>()
    /** Provider ids that are signed in. */
    val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Covers set on a playlist: (provider id, playlist id). */
    val coversSet: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())
    /** Titles each service's catalogue does not have (so a sync reports them "not found"). */
    val missing = ConcurrentHashMap<String, Set<String>>()
    /** While set, reading a playlist's tracks waits for it (suspended, no thread held): syncs stay running until the test lets them go. */
    @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // ---- the seed every test starts from

    const val SPOTIFY_ROAD = "1RoadTrip2026FakeList01"
    const val SPOTIFY_CHILL = "2ChillEveningFakeList02"
    const val SPOTIFY_DEAD = "3DeadTracksFakeList0003"
    const val DEEZER_WORKOUT = "908622995"
    const val YT_MIX = "PLfakeMetalMix2026xyz"

    /** Public playlists the share and card tests open by link. */
    const val PUBLIC_SPOTIFY = "0FriendsRockFakeList00"
    const val PUBLIC_DEEZER = "1313131313"
    const val PUBLIC_YOUTUBE = "PLfakePublicRock2026ab"
    const val PUBLIC_SOUNDCLOUD = "nightdriver/sets/night-drive"

    /** The playlist the Shazam app fills on Spotify once connected to it in Shazam's settings ([addShazamTracks]). */
    const val SPOTIFY_SHAZAM = "4MyShazamTracksFakeList"
    const val SHAZAM_NAME = "My Shazam Tracks"

    /** Songs 5 and 6 of the road trip are not on Deezer: a sync to Deezer finds 6 of 8. */
    val DEEZER_MISSING = setOf(Demo.SONGS[5].title, Demo.SONGS[6].title)

    fun reset() {
        playlists.clear(); public.clear(); connected.clear(); missing.clear(); coversSet.clear()
        hold?.complete(Unit); hold = null
        playlists["spotify"] = mutableListOf(
            FakePlaylist(SPOTIFY_ROAD, "Road Trip", songs("spotify", 0 until 8)),
            FakePlaylist(SPOTIFY_CHILL, "Chill Evening", songs("spotify", 8 until 13)),
            // Three entries Spotify no longer plays, between playable ones.
            FakePlaylist(SPOTIFY_DEAD, "Old Favourites", (songs("spotify", 13 until 18)).mapIndexed { i, t ->
                if (i == 1 || i == 3) t.copy(id = t.id + "x", available = false) else t
            }.toMutableList()),
        )
        playlists["deezer"] = mutableListOf(FakePlaylist(DEEZER_WORKOUT, "Workout", songs("deezer", 15 until 18)))
        playlists["ytm"] = mutableListOf(FakePlaylist(YT_MIX, "Metal Mix", songs("ytm", 0 until 4)))
        playlists["youtube"] = mutableListOf()
        public["spotify"] = mutableMapOf(PUBLIC_SPOTIFY to FakePlaylist(PUBLIC_SPOTIFY, "Friday Night Rock", songs("spotify", 2 until 12), owned = false))
        public["deezer"] = mutableMapOf(PUBLIC_DEEZER to FakePlaylist(PUBLIC_DEEZER, "Deezer Hits", songs("deezer", 0 until 6), owned = false))
        public["youtube"] = mutableMapOf(PUBLIC_YOUTUBE to FakePlaylist(PUBLIC_YOUTUBE, "YouTube Rock", songs("youtube", 4 until 10), owned = false))
        public["ytm"] = public.getValue("youtube")
        public["soundcloud"] = mutableMapOf(PUBLIC_SOUNDCLOUD to FakePlaylist(PUBLIC_SOUNDCLOUD, "Night Drive", songs("soundcloud", 6 until 11), owned = false))
        missing["deezer"] = DEEZER_MISSING
    }

    /**
     * Shazam connected to the fake Spotify: its "My Shazam Tracks" with four songs. Not in the seed,
     * so the other tests' Spotify keeps its three playlists.
     */
    fun addShazamTracks() {
        playlists.getValue("spotify") += FakePlaylist(SPOTIFY_SHAZAM, SHAZAM_NAME, songs("spotify", 3 until 7))
    }

    fun songs(providerId: String, range: IntRange): MutableList<Track> = range.map { song(providerId, it) }.toMutableList()

    /** The same recording on every fake service: same title, artist, album, length and ISRC; the id is the service's. */
    fun song(providerId: String, i: Int): Track {
        val s = Demo.SONGS[i % Demo.SONGS.size]
        return Track(id = trackId(providerId, i), title = s.title, artists = listOf(s.artist), album = s.album, durationMs = s.seconds * 1000L, isrc = s.isrc)
    }

    fun trackId(providerId: String, i: Int): String = when (providerId.substringBefore('@')) {
        "deezer" -> (3_135_556L + i).toString()
        "ytm", "youtube" -> "Yt" + "%09d".format(i)
        else -> "4uLU6hMCjMI75M" + "%08d".format(i)
    }

    fun playlist(providerId: String, name: String): FakePlaylist? = playlists[providerId]?.firstOrNull { it.name == name }

    // ---- the services

    // Spotify takes playlist covers (as since 1.16); YouTube Music lists followed podcasts, a 1.18 offer (ReleaseSwitchFlowTest).
    val spotify = FakeProvider("spotify", "Spotify", 0xFF1DB954, "provider_note_spotify", detectsUnplayable = true, canReorder = true, covers = true)
    val deezer = FakeProvider("deezer", "Deezer", 0xFFA238FF, "provider_note_deezer", detectsUnplayable = true, canReorder = true)
    val ytm = FakeProvider("ytm", "YouTube Music", 0xFFFF0000, "provider_note_ytm", route = MusicProvider.Route.EASY, podcasts = true)
    val youtube = FakeProvider("youtube", "YouTube Music", 0xFFFF0000, "provider_note_youtube", route = MusicProvider.Route.OFFICIAL)
    val jellyfin = FakeProvider("jellyfin", "Jellyfin", 0xFF00A4DC, "provider_note_jellyfin")
    val soundcloud = FakeProvider("soundcloud", "SoundCloud", 0xFFFF5500, "provider_note_soundcloud", linkOnly = true)

    /**
     * Installs the fakes in the place of the real connectors, in the same order and with the same
     * build rule as the app's own list (providers/Connectors.kt): SoundCloud's public links only in
     * the build that carries them.
     */
    fun install() {
        val f = listOf(spotify, ytm, youtube, deezer, jellyfin)
        Providers.install(
            f.map { p -> Providers.Factory(p.serviceId) { slot -> if (slot.isEmpty()) p else p.copyFor(slot) } },
            singletons = listOfNotNull(LocalFilesProvider, soundcloud.takeIf { com.xlollx.songport.providers.BuiltIn.available }),
        )
    }
}

/** One fake service (or a numbered extra account of one, [slot]). */
class FakeProvider(
    override val serviceId: String,
    override val displayName: String,
    override val brandColor: Long,
    override val noteName: String,
    override val detectsUnplayable: Boolean = false,
    override val canReorder: Boolean = false,
    override val route: MusicProvider.Route? = null,
    override val linkOnly: Boolean = false,
    override val slot: String = "",
    /** Followed podcasts as a source, offered from 1.18 (as YouTube Music's). */
    private val podcasts: Boolean = false,
    /** Takes playlist covers (as Spotify's official API since 1.16). */
    private val covers: Boolean = false,
) : MusicProvider, com.xlollx.songport.providers.PlayCounts {
    override val supportsPodcasts: Boolean get() = podcasts
    override fun supportsLibrary(playlistId: String?, asTarget: Boolean): Boolean =
        if (playlistId == MusicProvider.PODCASTS_ID && asTarget) false else super.supportsLibrary(playlistId, asTarget)
    override fun libraryIsV2(playlistId: String, asTarget: Boolean): Boolean = playlistId == MusicProvider.PODCASTS_ID
    override val canSetCover: Boolean get() = covers
    override suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) { FakeWorld.coversSet += id to playlistId }
    /** Jellyfin takes lyrics through its API (Tools › Synced lyrics, from 1.18). */
    override val lyricsSupport: com.xlollx.songport.providers.LyricsSupport
        get() = if (serviceId == "jellyfin") com.xlollx.songport.providers.LyricsSupport.SERVER_API else com.xlollx.songport.providers.LyricsSupport.NONE

    /** A personal server's play counts (Jellyfin's, for the Wrapped and the poster from 1.18): none counted yet. */
    override suspend fun playlistPlays(ctx: Env, playlistId: String): List<com.xlollx.songport.sync.Listening.Play> = emptyList()
    override suspend fun topPlays(ctx: Env, n: Int): List<com.xlollx.songport.sync.Listening.Play> = emptyList()

    override val familyName: String get() = displayName
    override val requiresAuth: Boolean get() = !linkOnly
    override val canWrite: Boolean get() = !linkOnly
    override val canCreatePlaylists: Boolean get() = !linkOnly
    override val supportsMultipleAccounts: Boolean get() = !linkOnly

    fun copyFor(slot: String) = FakeProvider(serviceId, displayName, brandColor, noteName, detectsUnplayable, canReorder, route, linkOnly, slot, podcasts, covers)

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = id in FakeWorld.connected
    override fun canRead(ctx: Env, playlistId: String) = linkOnly || isConnected(ctx) || FakeWorld.public[serviceId]?.containsKey(playlistId) == true
    override fun accountName(ctx: Env): String? = if (isConnected(ctx)) "alex" else null
    /** The sign-in "returns" at once, the way the loopback flow reports back: through AuthEvents. */
    override fun startAuth(ctx: Env) {
        FakeWorld.connected += id
        com.xlollx.songport.auth.AuthEvents.post("$displayName: signed in (fake)")
    }
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) { FakeWorld.connected += id }
    override fun disconnect(ctx: Env) { FakeWorld.connected -= id }

    private fun own(): MutableList<FakeWorld.FakePlaylist> = FakeWorld.playlists.getOrPut(id) { mutableListOf() }

    private fun find(playlistId: String): FakeWorld.FakePlaylist =
        own().firstOrNull { it.id == playlistId } ?: FakeWorld.public[serviceId]?.get(playlistId)
            ?: throw ProviderException("$displayName: no playlist $playlistId")

    override suspend fun playlists(ctx: Env): List<Playlist> = own().map { Playlist(it.id, it.name, it.tracks.size, it.owned) }

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = find(playlistId).let { Playlist(it.id, it.name, it.tracks.size, it.owned) }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        FakeWorld.hold?.await()
        return synchronized(FakeWorld) { find(playlistId).tracks.toList() }
    }

    private fun catalogue(): List<Track> {
        val gone = FakeWorld.missing[serviceId].orEmpty()
        return Demo.SONGS.indices.map { FakeWorld.song(id, it) }.filter { it.title !in gone }
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = catalogue().firstOrNull { it.id == trackId }

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val wanted = Matcher.normalizeTitle(track.title)
        return catalogue().filter { Matcher.normalizeTitle(it.title) == wanted }
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist {
        val p = FakeWorld.FakePlaylist("new-" + System.nanoTime(), name, mutableListOf())
        synchronized(FakeWorld) { own() += p }
        return Playlist(p.id, p.name, 0)
    }

    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) { synchronized(FakeWorld) { find(playlistId).tracks += tracks } }

    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        val ids = tracks.map { it.id }.toSet()
        synchronized(FakeWorld) { find(playlistId).tracks.removeAll { it.id in ids } }
    }

    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {
        synchronized(FakeWorld) { val p = find(playlistId); p.tracks.clear(); p.tracks += ordered }
    }

    override suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) { find(playlistId).name = name }
    override suspend fun deletePlaylist(ctx: Env, playlistId: String) { synchronized(FakeWorld) { own().removeAll { it.id == playlistId } } }

    override fun webSearchUrl(ctx: Env, query: String): String? = null
}
