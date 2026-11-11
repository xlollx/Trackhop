package com.xlollx.songport

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.Unplayable

/**
 * A connected service for the store screenshots: the real service id (so the real logo and name
 * show), canned playlists, a small catalogue to match against, and no network at all.
 */
class ShotProvider(
    override val serviceId: String,
    override val displayName: String,
    override val brandColor: Long,
    private val idStyle: IdStyle,
    override val familyName: String = displayName,
) : MusicProvider {
    enum class IdStyle { SPOTIFY, YOUTUBE, NUMERIC, ASIN, UUID }

    override val noteName: String = ""
    override val supportsLikedSongs: Boolean = true
    override val canReorder: Boolean = true
    override val detectsUnplayable: Boolean get() = serviceId == "spotify"

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun canRead(ctx: Env, playlistId: String) = true
    override fun accountName(ctx: Env): String? = "alex"
    override fun startAuth(ctx: Env) {}
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
    override fun disconnect(ctx: Env) {}

    fun id(i: Int): String = when (idStyle) {
        IdStyle.SPOTIFY -> "4uLU6hMCjMI75M" + "%08d".format(i)
        IdStyle.YOUTUBE -> "Sp" + "%09d".format(i)
        IdStyle.NUMERIC -> (3_135_556L + i).toString()
        IdStyle.ASIN -> "B0" + "%08d".format(i)
        IdStyle.UUID -> "8f1c2d3e-%04d-4b5a-9c8d-%012d".format(i % 10000, i)
    }

    private fun track(i: Int): Track {
        val s = Demo.SONGS[i % Demo.SONGS.size]
        return Track(id = id(i), title = s.title, artists = listOf(s.artist), album = s.album, durationMs = s.seconds * 1000L, isrc = s.isrc)
    }

    private fun list(size: Int, offset: Int = 0) = (0 until size).map { track(offset + it) }

    override suspend fun playlists(ctx: Env): List<Playlist> = Demo.PLAYLISTS.map { (id, name, n) -> Playlist(id, name, n) } +
        (if (Demo.carouselOn) listOf(Demo.CAROUSEL_SRC, Demo.CAROUSEL_DST).map { Playlist(it, Demo.CAROUSEL_NAME, Demo.carousel(it)!!.size) } else emptyList())

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist =
        Demo.PLAYLISTS.firstOrNull { it.first == playlistId }?.let { Playlist(it.first, it.second, it.third) }
            ?: Demo.LINKED[playlistId]?.let { Playlist(playlistId, it.first, it.second) }
            ?: Demo.carousel(playlistId)?.let { Playlist(playlistId, Demo.CAROUSEL_NAME, it.size) }
            ?: Playlist(playlistId, "Metal & More", 1152)

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> {
        // The friend's playlist of the comparison: some songs in common, most of its own.
        if (playlistId == Demo.OTHER_LINK_ID) return (0 until 30).map { i ->
            if (i % 5 == 0 || i % 5 == 3) track(i * 3 + 1)
            else Demo.EXTRA[i % Demo.EXTRA.size].let { s ->
                Track(id = id(900 + i), title = s.title, artists = listOf(s.artist), album = s.album, durationMs = s.seconds * 1000L, isrc = s.isrc)
            }
        }
        // The carousel's sync to preview: some songs to add, some to take off, two the target lacks.
        Demo.carousel(playlistId)?.let { songs -> return songs.map { (n, s) -> if (s == null) track(n) else Track(id = id(n), title = s.title, artists = listOf(s.artist), album = s.album, durationMs = s.seconds * 1000L, isrc = s.isrc) } }
        val size = playlistInfo(ctx, playlistId).trackCount.coerceAtLeast(1)
        return list(size)
    }

    override suspend fun track(ctx: Env, trackId: String): Track? = list(Demo.SONGS.size).firstOrNull { it.id == trackId } ?: track(0)

    override suspend fun search(ctx: Env, track: Track): List<Track> {
        val wanted = Matcher.normalizeTitle(track.title)
        return Demo.SONGS.indices.map { track(it + 400) }.filter { Matcher.normalizeTitle(it.title) == wanted }
    }

    override suspend fun unplayable(ctx: Env, playlistId: String): List<UnplayableEntry> {
        if (playlistId != Demo.PLAYLISTS[1].first) return emptyList()
        return listOf(
            UnplayableEntry(track(3).copy(available = false), 3, Unplayable.REASON_MARKET),
            UnplayableEntry(track(7).copy(available = false), 7, Unplayable.REASON_DELETED),
            UnplayableEntry(track(11).copy(available = false), 11, Unplayable.REASON_PRODUCT),
        )
    }

    override suspend fun createPlaylist(ctx: Env, name: String, description: String) = Playlist(name, name, 0)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    override suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {}
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? = "v1"

    @Suppress("unused") private fun fail(): Nothing = throw ProviderException("demo")
}

/** The music the demo library is made of: real songs, so the screens read like a real library. */
object Demo {
    class Song(val title: String, val artist: String, val album: String, val seconds: Int, val isrc: String)

    val SONGS = listOf(
        Song("Master of Puppets", "Metallica", "Master of Puppets", 515, "USEE10001992"),
        Song("Hail to the King", "Avenged Sevenfold", "Hail to the King", 305, "USRE11300571"),
        Song("Chop Suey!", "System of a Down", "Toxicity", 210, "USSM10107264"),
        Song("Numb", "Linkin Park", "Meteora", 186, "USWB10301217"),
        Song("The Diary of Jane", "Breaking Benjamin", "Phobia", 200, "USHR10622153"),
        Song("Duality", "Slipknot", "Vol. 3: (The Subliminal Verses)", 252, "NLA320400153"),
        Song("Enter Sandman", "Metallica", "Metallica", 331, "USEE19100011"),
        Song("Toxicity", "System of a Down", "Toxicity", 219, "USSM10107272"),
        Song("In the End", "Linkin Park", "Hybrid Theory", 216, "USWB10002407"),
        Song("Psychosocial", "Slipknot", "All Hope Is Gone", 283, "NLA320882413"),
        Song("Nightmare", "Avenged Sevenfold", "Nightmare", 374, "USRE11000484"),
        Song("Paranoid", "Black Sabbath", "Paranoid", 168, "GBAJE7000017"),
        Song("Ace of Spades", "Motörhead", "Ace of Spades", 169, "GBCCS8000005"),
        Song("The Trooper", "Iron Maiden", "Piece of Mind", 250, "GBAYE9800213"),
        Song("Painkiller", "Judas Priest", "Painkiller", 366, "GBBBN9000019"),
        Song("Walk", "Pantera", "Vulgar Display of Power", 315, "USEE19200087"),
        Song("Bring Me to Life", "Evanescence", "Fallen", 237, "USWU30200093"),
        Song("Before I Forget", "Slipknot", "Vol. 3: (The Subliminal Verses)", 278, "NLA320400147"),
        Song("Afterlife", "Avenged Sevenfold", "Avenged Sevenfold", 351, "USRE10700569"),
        Song("Down with the Sickness", "Disturbed", "The Sickness", 278, "USRE10001060"),
    )

    /** Songs only the friend's playlist has. */
    val EXTRA = listOf(
        Song("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354, "GBUM71029604"),
        Song("Smells Like Teen Spirit", "Nirvana", "Nevermind", 301, "USGF19942501"),
        Song("Seven Nation Army", "The White Stripes", "Elephant", 232, "USVT10300001"),
        Song("Mr. Brightside", "The Killers", "Hot Fuss", 222, "GBFFP0300052"),
        Song("Back in Black", "AC/DC", "Back in Black", 255, "AUAP08000046"),
        Song("Everlong", "Foo Fighters", "The Colour and the Shape", 250, "USRW29600011"),
        Song("Black Hole Sun", "Soundgarden", "Superunknown", 318, "USAM19400262"),
        Song("Welcome to the Jungle", "Guns N' Roses", "Appetite for Destruction", 273, "USGF18714801"),
        Song("Kryptonite", "3 Doors Down", "The Better Life", 233, "USUR19980187"),
        Song("Basket Case", "Green Day", "Dookie", 181, "USRE19900154"),
        Song("Zombie", "The Cranberries", "No Need to Argue", 306, "IEISL9400005"),
        Song("Last Resort", "Papa Roach", "Infest", 199, "USDW10021358"),
        Song("Nothing Else Matters", "Metallica", "Metallica", 388, "USEE19100014"),
        Song("Aerials", "System of a Down", "Toxicity", 235, "USSM10107277"),
    )

    /** (id, name, track count) of the demo library. */
    val PLAYLISTS = listOf(
        Triple("37i9dQZF1DX9qNs32fujYe", "Metal & More", 1152),
        Triple("6UeSakyzhiEt4NB3UAd6NQ", "Road Trip 2026", 73),
        Triple("1h0CEZCm6IbFTbxThn6Xcs", "Chill Evening", 48),
        Triple("4cRo44TavIHN54w46OqRVc", "Workout", 60),
    )

    const val LINK_ID = "PLDemoMetalAndMore2026xx"
    const val OTHER_LINK_ID = "0vvXsWCC9xrXsKd4FyS8kM"
    /** Public playlists opened from a link, by id: (name, track count). */
    /** The carousel's extra sync (Spotify to YouTube Music): its source and target. */
    const val CAROUSEL_SRC = "5aGvC4rouSe1Src0000000"
    const val CAROUSEL_DST = "PLcarouselSummerVibes"
    const val CAROUSEL_NAME = "Summer Vibes"
    /** The carousel's pictures are being taken: its playlists are in the library too. */
    @Volatile var carouselOn = false
    /** Songs only the carousel's target still holds (taken off by the next run). */
    private val GONE = listOf(
        Song("Hysteria", "Muse", "Absolution", 227, "GBAHT0300392"),
        Song("Chalk Outline", "Three Days Grace", "Transit of Venus", 186, "USJI11200401"),
        Song("Lithium", "Nirvana", "Nevermind", 257, "USGF19942504"),
    )

    /** The carousel playlists as (track number, song or null for the demo's song at that number). */
    fun carousel(playlistId: String): List<Pair<Int, Song?>>? = when (playlistId) {
        CAROUSEL_SRC -> (0 until 20).map { it to null } + EXTRA.take(2).mapIndexed { i, s -> 950 + i to s }
        CAROUSEL_DST -> (0 until 12).map { it to null } + GONE.mapIndexed { i, s -> 980 + i to s }
        else -> null
    }

    val LINKED = mapOf(LINK_ID to ("Metal & More" to 1152), OTHER_LINK_ID to ("Friday Night Rock" to 60))
}
