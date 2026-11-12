package com.xlollx.songport.it

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.SubsonicProvider
import com.xlollx.songport.sync.PlaylistFiles
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.it.LiveServers.AIRSONIC
import com.xlollx.songport.it.LiveServers.AMPACHE
import com.xlollx.songport.it.LiveServers.GONIC
import com.xlollx.songport.it.LiveServers.JELLYFIN
import com.xlollx.songport.it.LiveServers.JELLYFIN108
import com.xlollx.songport.it.LiveServers.NAVIDROME
import com.xlollx.songport.it.LiveServers.PLEX
import com.xlollx.songport.it.LiveServers.SUBSONIC
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** A server under test, connected as the user would connect it. */
class LiveServer(
    val key: String, val label: String, private val make: () -> CredentialsProvider,
    val listsIsrc: Boolean = false,
    /** False where the server reorders a playlist on every write (Airsonic-Advanced 11): only the content is compared. */
    val keepsOrder: Boolean = true,
) {
    fun env(name: String): String = System.getenv("SONGPORT_IT_${key}_$name").orEmpty()
    val configured: Boolean get() = env("URL").isNotBlank()
    suspend fun connect(ctx: Env): CredentialsProvider =
        make().also { it.login(ctx, env("URL"), env("USER"), env("PASS").ifEmpty { env("TOKEN") }) }
    override fun toString() = label
}

object LiveServers {
    val NAVIDROME = LiveServer("NAVIDROME", "Navidrome", { SubsonicProvider("navidrome") }, listsIsrc = true)
    val GONIC = LiveServer("GONIC", "Gonic", { SubsonicProvider("gonic") }, listsIsrc = true)
    val AIRSONIC = LiveServer("AIRSONIC", "Airsonic-Advanced", { SubsonicProvider("airsonic") }, keepsOrder = false)
    val SUBSONIC = LiveServer("SUBSONIC", "Subsonic", { SubsonicProvider("subsonic") })
    val AMPACHE = LiveServer("AMPACHE", "Ampache", { SubsonicProvider("ampache") }, listsIsrc = true)
    val JELLYFIN = LiveServer("JELLYFIN", "Jellyfin", { JellyfinProvider("jellyfin") })
    val JELLYFIN108 = LiveServer("JELLYFIN108", "Jellyfin 10.8", { JellyfinProvider("jellyfin108") })
    val PLEX = LiveServer("PLEX", "Plex", { PlexProvider("plex") })
    val ALL = listOf(NAVIDROME, GONIC, AIRSONIC, SUBSONIC, AMPACHE, JELLYFIN, JELLYFIN108, PLEX)
}

/**
 * The self-hosted connectors against real servers, driven the way the app drives them: login with
 * the credentials form, then the provider API and the sync engine.
 *
 * Each server is skipped unless it is given, so CI and F-Droid builds never need one:
 *   SONGPORT_IT_<SERVER>_URL / _USER / _PASS (Plex: _URL / _TOKEN), SERVER one of NAVIDROME,
 *   GONIC, AIRSONIC, SUBSONIC, AMPACHE, JELLYFIN, JELLYFIN108, PLEX.
 * scripts/live_servers.sh up [servers] starts them with the same library and prints these: 12
 * tagged songs by Metallica, Beyoncé, Måneskin and Daft Punk, the same files on every server.
 */
@RunWith(Enclosed::class)
class LiveServersIT {

    /** What every test needs: a platform, connected providers, and the clean-up of what a test made. */
    abstract class Harness {
        protected val ctx = TestEnv()
        protected val run = UUID.randomUUID().toString().take(6)
        private val made = mutableListOf<Pair<MusicProvider, String>>()
        private val connected = mutableListOf<MusicProvider>()

        init { FileStore.install(ctx.store) }

        protected fun connect(vararg servers: LiveServer): List<MusicProvider> {
            servers.forEach { assumeTrue("${it.label} not configured", it.configured) }
            val ps = runBlocking { servers.map { it.connect(ctx) } }
            connected += ps
            Providers.testDoubles = connected + LocalFilesProvider
            return ps
        }

        @After fun tearDown() {
            runBlocking {
                made.forEach { (p, id) -> runCatching { p.deletePlaylist(ctx, id) } }
                // Leave the favourites as found: nothing starred.
                connected.filter { it.supportsLikedSongs }
                    .forEach { p -> runCatching { p.removeTracks(ctx, MusicProvider.LIKED_ID, p.tracks(ctx, MusicProvider.LIKED_ID)) } }
            }
            Providers.testDoubles = emptyList()
        }

        protected suspend fun song(p: MusicProvider, artist: String, title: String): Track =
            p.search(ctx, Track(id = "q", title = title, artists = listOf(artist), album = "", durationMs = 0))
                .firstOrNull { it.title == title && it.artists.any { a -> a == artist } }
                ?: throw AssertionError("${p.displayName}: search for $artist – $title found nothing usable")

        protected fun names(ts: List<Track>) = ts.map { "${it.artists.firstOrNull()} – ${it.title}" }

        /** [expected] and [actual] in order, or as the same entries in any order where [server] does not keep it. */
        protected fun <T : Comparable<T>> assertEntries(message: String, server: LiveServer, expected: List<T>, actual: List<T>) =
            if (server.keepsOrder) assertEquals(message, expected, actual) else assertEquals(message, expected.sorted(), actual.sorted())

        /** A new playlist, removed after the test. */
        protected suspend fun newPlaylist(p: MusicProvider, name: String): Playlist {
            val pl = p.createPlaylist(ctx, name, "")
            made += p to pl.id
            return pl
        }

        protected fun forget(p: MusicProvider, id: String) { made.remove(p to id) }
        protected fun keep(p: MusicProvider, id: String) { made += p to id }

        protected fun job(src: MusicProvider, srcId: String, dst: MusicProvider, dstId: String?, mirror: Boolean): SyncJob {
            val j = SyncJob(
                id = UUID.randomUUID().toString(), name = "IT $run",
                source = PlaylistRef(src.id, srcId, srcId), target = PlaylistRef(dst.id, dstId, dstId ?: "IT $run target"),
                mirrorRemovals = mirror,
            )
            ctx.store.upsertJob(j)
            return j
        }
    }

    // ------------------------------------------------------------------ each server on its own

    @RunWith(Parameterized::class)
    class EachServer(private val server: LiveServer) : Harness() {
        companion object {
            @JvmStatic @Parameterized.Parameters(name = "{0}") fun servers() = LiveServers.ALL
        }

        private val p by lazy { connect(server).single() }

        @Test fun search() = runBlocking {
            song(p, "Metallica", "Battery")                                   // exact
            song(p, "Beyoncé", "Café Noir")                                   // accents
            song(p, "Beyoncé", "Énergie (feat. Pharrell)")                    // feat. in the title
            song(p, "Metallica", "Orion (Remastered 2017)")                   // remaster suffix
            song(p, "Måneskin", "Home"); song(p, "Beyoncé", "Home")          // same title, two artists
            // As a sync searches: the source's spelling, without the decorations.
            val viaPlain = p.search(ctx, Track(id = "q", title = "Orion", artists = listOf("Metallica")))
            assertTrue("$server: 'Orion' should find the remaster, got ${names(viaPlain)}", viaPlain.any { it.title.startsWith("Orion") })
            val noAccent = p.search(ctx, Track(id = "q", title = "Cafe Noir", artists = listOf("Beyonce")))
            println("$server: unaccented search -> ${names(noAccent)}")
        }

        @Test fun playlists() = runBlocking {
            val name = "IT $run ${server.key}"
            val pl = newPlaylist(p, name)
            assertTrue("$server: new playlist listed", p.playlists(ctx).any { it.id == pl.id && it.name == name })
            val a = song(p, "Daft Punk", "One More Time"); val b = song(p, "Måneskin", "Coraline"); val c = song(p, "Metallica", "Battery")
            p.addTracks(ctx, pl.id, listOf(a, b, c))
            p.addTracks(ctx, pl.id, listOf(b)) // a duplicate, as a user could have
            val read = p.tracks(ctx, pl.id)
            // Jellyfin 10.8 keeps one copy of a song per playlist: the second add changes nothing there.
            val copies = read.size == 4
            if (!copies) println("$server: a song added twice is in the playlist once")
            val all = if (copies) listOf(a, b, c, b) else listOf(a, b, c)
            assertEntries("$server: order after adds", server, all.map { it.id }, read.map { it.id })
            assertTrue("$server: durations read", read.all { it.durationMs in 4_000..12_000 })
            assertEntries("$server: names read", server, names(all), names(read))
            // Remove one Coraline and Battery: one call, positions or entry ids as read.
            p.removeTracks(ctx, pl.id, listOf(read.first { it.id == b.id }, read.first { it.id == c.id }))
            assertEntries("$server: after removing two entries", server, (if (copies) listOf(a, b) else listOf(a)).map { it.id }, p.tracks(ctx, pl.id).map { it.id })
            if (copies) {
                // And the copy that is left of the song that was in twice.
                p.removeTracks(ctx, pl.id, listOf(p.tracks(ctx, pl.id).first { it.id == b.id }))
                assertEquals("$server: after removing the other copy", listOf(a.id), p.tracks(ctx, pl.id).map { it.id })
            }
            if (p.canRenamePlaylists) {
                p.renamePlaylist(ctx, pl.id, "$name renamed")
                assertTrue("$server: renamed", p.playlists(ctx).any { it.id == pl.id && it.name == "$name renamed" })
            }
            if (p.canDeletePlaylists) {
                p.deletePlaylist(ctx, pl.id); forget(p, pl.id)
                assertTrue("$server: deleted", p.playlists(ctx).none { it.id == pl.id })
            }
        }

        @Test fun likes() = runBlocking {
            assumeTrue("$server: no favourites in the connector", p.supportsLikedSongs)
            val a = song(p, "Beyoncé", "Home"); val b = song(p, "Daft Punk", "Digital Love")
            p.addTracks(ctx, MusicProvider.LIKED_ID, listOf(a, b))
            assertEquals("$server: starred", setOf(a.id, b.id), p.tracks(ctx, MusicProvider.LIKED_ID).map { it.id }.toSet())
            p.removeTracks(ctx, MusicProvider.LIKED_ID, listOf(a))
            assertEquals("$server: unstarred one", setOf(b.id), p.tracks(ctx, MusicProvider.LIKED_ID).map { it.id }.toSet())
        }

        @Test fun readsIsrc() = runBlocking {
            assumeTrue("$server does not list ISRCs", server.listsIsrc)
            assertEquals("$server lists the ISRC of the file (OpenSubsonic)", "USEE10001001", song(p, "Metallica", "Battery").isrc)
        }
    }

    // ------------------------------------------------------------------ Ampache's own sign-in rule

    class AmpacheLogin : Harness() {
        /** Ampache answers error 40 with type "ampache": the login says where its Subsonic password is set. */
        @Test fun wrongPasswordNamesTheSubsonicPassword() = runBlocking {
            assumeTrue("Ampache not configured", AMPACHE.configured)
            val e = runCatching { SubsonicProvider("ampache-wrong").login(ctx, AMPACHE.env("URL"), AMPACHE.env("USER"), "not-the-subsonic-password") }.exceptionOrNull()
            assertEquals("error_ampache_subsonic_password", e?.message)
        }
    }

    // ------------------------------------------------------------------ the engine, between servers

    class Syncs : Harness() {
        private val picks = listOf(
            "Metallica" to "Battery", "Beyoncé" to "Café Noir", "Beyoncé" to "Énergie (feat. Pharrell)",
            "Metallica" to "Orion (Remastered 2017)", "Måneskin" to "Home", "Beyoncé" to "Home",
        )
        private val expected = picks.map { (a, t) -> "$a – $t" }

        /**
         * A six-song playlist from [srcServer] to [dstServer]: all six arrive in order, a second run
         * changes nothing, two songs removed at the source go from the target too, and a fourth run
         * changes nothing again.
         */
        private fun playlistSync(srcServer: LiveServer, dstServer: LiveServer) = runBlocking {
            val (src, dst) = connect(srcServer, dstServer)
            val engine = SyncEngine(ctx)
            val s = newPlaylist(src, "IT $run source")
            val tracks = picks.map { (a, t) -> song(src, a, t) }
            src.addTracks(ctx, s.id, tracks)
            // A target the connector cannot make is made first, empty, and picked.
            val pre = if (dst.canCreatePlaylists) null else newPlaylist(dst, "IT $run target").id
            val j = job(src, s.id, dst, pre, mirror = true)
            val first = engine.run(j)
            val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched} review=${first.reviewTracks.size}"
            assertEquals(why, null, first.error)
            assertEquals(why, 6, first.added)
            val target = ctx.store.job(j.id)!!.target.playlistId!!
            if (pre == null) keep(dst, target)
            assertEntries(why, if (srcServer.keepsOrder) dstServer else srcServer, expected, names(dst.tracks(ctx, target)))
            val again = engine.run(ctx.store.job(j.id)!!)
            assertEquals("second run: ${again.notes} ${again.error}", null, again.error)
            assertEquals("second run: ${again.notes} ${again.error}", 0, again.added + again.removed)
            val read = src.tracks(ctx, s.id)
            src.removeTracks(ctx, s.id, listOf(read[0], read[4]))
            val third = engine.run(ctx.store.job(j.id)!!)
            assertEquals("mirror: ${third.notes} ${third.error}", 2, third.removed)
            assertEquals("mirror", 4, dst.tracks(ctx, target).size)
            assertEntries("mirror", if (srcServer.keepsOrder) dstServer else srcServer, names(src.tracks(ctx, s.id)), names(dst.tracks(ctx, target)))
            val fourth = engine.run(ctx.store.job(j.id)!!)
            assertEquals("after the mirror: ${fourth.notes} ${fourth.error}", 0, fourth.added + fourth.removed)
        }

        /** Three favourites from [srcServer] starred on [dstServer]; a second run adds nothing. */
        private fun likesSync(srcServer: LiveServer, dstServer: LiveServer) = runBlocking {
            val (src, dst) = connect(srcServer, dstServer)
            val engine = SyncEngine(ctx)
            val favs = listOf(song(src, "Daft Punk", "Something About Us (feat. Romanthony)"), song(src, "Måneskin", "Zitti e buoni"), song(src, "Metallica", "Master of Puppets"))
            src.addTracks(ctx, MusicProvider.LIKED_ID, favs)
            val j = job(src, MusicProvider.LIKED_ID, dst, MusicProvider.LIKED_ID, mirror = false)
            val first = engine.run(j)
            val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched}"
            assertEquals(why, null, first.error)
            assertEquals(why, 3, first.added)
            assertEquals(names(favs).toSet(), names(dst.tracks(ctx, MusicProvider.LIKED_ID)).toSet())
            val again = engine.run(ctx.store.job(j.id)!!)
            assertEquals("second run: ${again.notes}", 0, again.added)
        }

        @Test fun navidromeToJellyfin() = playlistSync(NAVIDROME, JELLYFIN)
        @Test fun plexToGonic() = playlistSync(PLEX, GONIC)
        @Test fun gonicToPlex() = playlistSync(GONIC, PLEX)
        @Test fun airsonicToJellyfin108() = playlistSync(AIRSONIC, JELLYFIN108)
        @Test fun jellyfin108ToSubsonic() = playlistSync(JELLYFIN108, SUBSONIC)
        @Test fun subsonicToAirsonic() = playlistSync(SUBSONIC, AIRSONIC)
        @Test fun ampacheToPlex() = playlistSync(AMPACHE, PLEX)
        @Test fun plexToAmpache() = playlistSync(PLEX, AMPACHE)

        @Test fun jellyfinFavouritesToNavidrome() = likesSync(JELLYFIN, NAVIDROME)
        @Test fun gonicFavouritesToJellyfin108() = likesSync(GONIC, JELLYFIN108)
        @Test fun airsonicFavouritesToSubsonic() = likesSync(AIRSONIC, SUBSONIC)

        // -------------------------------------------------------------- files

        /** The servers' layout, Artist/Album/NN Title.ext, as real files next to the playlist file. */
        private val library = listOf(
            "Metallica/Master of Puppets/01 Battery.mp3", "Beyoncé/Renaissance/01 Café Noir.flac",
            "Beyoncé/Renaissance/02 Énergie (feat. Pharrell).flac", "Metallica/Master of Puppets/03 Orion (Remastered 2017).mp3",
            "Måneskin/Teatro d'ira/03 Home.mp3", "Beyoncé/Renaissance/03 Home.flac",
        )

        /** A playlist file as a player writes it, imported as the app imports a picked file. {dir} is the folder. */
        private fun importFile(name: String, text: String, bom: Boolean = false): String {
            val dir = Files.createTempDirectory("songport-files").toFile()
            library.forEach { File(dir, "music/$it").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) } }
            val f = File(dir, name)
            val bytes = text.replace("{dir}", dir.path).toByteArray()
            f.writeBytes(if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes else bytes)
            LocalFilesProvider.importFile(ctx, f.name, PlaylistFiles.decode(f.readBytes()))
            return runBlocking { LocalFilesProvider.playlists(ctx) }.single { it.name == f.nameWithoutExtension }.id
        }

        private fun filesTo(dstServer: LiveServer, file: () -> String) = runBlocking {
            val (dst) = connect(dstServer)
            val fileId = file()
            val files = LocalFilesProvider
            assertEquals("the file as imported", expected, names(files.tracks(ctx, fileId)))
            val engine = SyncEngine(ctx)
            val pre = if (dst.canCreatePlaylists) null else newPlaylist(dst, "IT $run target").id
            val j = job(files, fileId, dst, pre, mirror = true)
            val first = engine.run(j)
            val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched} review=${first.reviewTracks.size}"
            assertEquals(why, null, first.error)
            assertEquals(why, 6, first.added)
            val target = ctx.store.job(j.id)!!.target.playlistId!!
            if (pre == null) keep(dst, target)
            assertEntries(why, dstServer, expected, names(dst.tracks(ctx, target)))
            val again = engine.run(ctx.store.job(j.id)!!)
            assertEquals("second run: ${again.notes} ${again.error}", 0, again.added + again.removed)
            files.removeTracks(ctx, fileId, files.tracks(ctx, fileId).let { listOf(it[0], it[5]) })
            val third = engine.run(ctx.store.job(j.id)!!)
            assertEquals("mirror: ${third.notes} ${third.error}", 2, third.removed)
            assertEntries("mirror", dstServer, names(files.tracks(ctx, fileId)), names(dst.tracks(ctx, target)))
        }

        private val m3uExtinf = "#EXTM3U\r\n" + library.zip(picks).joinToString("") { (path, at) ->
            "#EXTINF:8,${at.first} - ${at.second}\r\nmusic/$path\r\n"
        }
        private val m3uPathsOnly = library.joinToString("\n", postfix = "\n") { "{dir}/music/$it" }
        private val pls = "[playlist]\r\n" + library.mapIndexed { i, path -> "File${i + 1}=music\\${path.replace('/', '\\')}\r\nLength${i + 1}=8\r\n" }.joinToString("") +
            "NumberOfEntries=${library.size}\r\nVersion=2\r\n"

        @Test fun m3u8WithBomAndCrlfToGonic() = filesTo(GONIC) { importFile("IT extinf.m3u8", m3uExtinf, bom = true) }
        @Test fun m3uPathsOnlyToJellyfin108() = filesTo(JELLYFIN108) { importFile("IT paths.m3u", m3uPathsOnly) }
        @Test fun m3uPathsOnlyToJellyfin() = filesTo(JELLYFIN) { importFile("IT paths.m3u", m3uPathsOnly) }
        @Test fun plsToSubsonic() = filesTo(SUBSONIC) { importFile("IT list.pls", pls) }
        @Test fun m3u8ToPlex() = filesTo(PLEX) { importFile("IT extinf.m3u8", m3uExtinf) }

        /**
         * A sync to "a new playlist" on Plex: the connector makes it under the name asked for, with
         * Trackhop's description as its summary, and the engine fills it; a second run changes nothing.
         */
        @Test fun newPlaylistOnPlex() = runBlocking {
            val (plex) = connect(PLEX)
            val fileId = importFile("IT new on plex.m3u8", m3uExtinf)
            assertTrue("Plex makes its own playlists", plex.canCreatePlaylists)
            val j = job(LocalFilesProvider, fileId, plex, null, mirror = true)
            val first = SyncEngine(ctx).run(j)
            val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched} review=${first.reviewTracks.size}"
            assertEquals(why, null, first.error)
            assertEquals(why, 6, first.added)
            val target = ctx.store.job(j.id)!!.target.playlistId!!
            keep(plex, target)
            val listed = plex.playlists(ctx).single { it.id == target }
            assertEquals("IT $run target", listed.name)
            assertEquals(6, listed.trackCount)
            assertEquals(expected, names(plex.tracks(ctx, target)))
            val t = ctx.tokens.get(plex.id)!!
            val summary = Http.send("GET", "${t.extra["url"]}/playlists/$target", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"))
                .let { parseJson(it.body)["MediaContainer"]["Metadata"][0]["summary"].str }
            assertEquals("the description", MusicProvider.description(ctx.text("app_name")), summary)
            assertEquals(0, SyncEngine(ctx).run(ctx.store.job(j.id)!!).let { it.added + it.removed })
        }

        /** A server playlist to a file, exported as M3U and imported again: the same six songs. */
        @Test fun plexToFileAndBack() = runBlocking {
            val (plex) = connect(PLEX)
            val s = newPlaylist(plex, "IT $run source")
            plex.addTracks(ctx, s.id, picks.map { (a, t) -> song(plex, a, t) })
            val j = job(plex, s.id, LocalFilesProvider, null, mirror = true)
            val first = SyncEngine(ctx).run(j)
            assertEquals("error=${first.error} notes=${first.notes}", 6, first.added)
            val fileId = ctx.store.job(j.id)!!.target.playlistId!!
            assertEquals(expected, names(LocalFilesProvider.tracks(ctx, fileId)))
            val m3u = LocalFilesProvider.exportText(ctx, fileId, LocalFilesProvider.Export.M3U)
            val back = importFile("IT round trip.m3u8", m3u)
            assertEquals(expected, names(LocalFilesProvider.tracks(ctx, back)))
            assertEquals(0, SyncEngine(ctx).run(ctx.store.job(j.id)!!).let { it.added + it.removed })
        }
    }
}

/** The platform for these tests: a temporary folder, tokens in memory, texts by name. */
class TestEnv : Env {
    override val dataDir: File = Files.createTempDirectory("songport-it").toFile()
    override val store = FileStore(dataDir)
    private val vault = HashMap<String, Tokens>()
    override val tokens = object : TokenVault {
        override fun get(providerId: String) = vault[providerId]
        override fun set(providerId: String, tokens: Tokens) { vault[providerId] = tokens }
        override fun clear(providerId: String) { vault.remove(providerId) }
    }
    override val redirectUri = ""
    override fun text(name: String, vararg args: Any) = name + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")
    override fun log(tag: String, message: String) = println("[$tag] $message")
    override fun startAuth(provider: MusicProvider, mode: AuthMode) = Unit
    private val kv = HashMap<String, KeyValues>()
    override fun keyValues(name: String): KeyValues = kv.getOrPut(name) {
        val m = HashMap<String, String>()
        object : KeyValues {
            override fun get(key: String) = m[key]
            override fun set(key: String, value: String) { m[key] = value }
            override val size get() = m.size
            override fun clear() = m.clear()
        }
    }
    override fun squareJpeg(raw: ByteArray, minSide: Int, maxSide: Int, maxBytes: Int): ByteArray? = null
}
