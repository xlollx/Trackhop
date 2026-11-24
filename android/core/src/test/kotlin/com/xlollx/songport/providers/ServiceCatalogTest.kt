package com.xlollx.songport.providers

import com.xlollx.songport.providers.ServiceCatalog.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Which service?": categories, other names, regional services at home, and the blocks of each chip. */
class ServiceCatalogTest {
    private val services: List<MusicProvider> = listOf(
        SpotifyProvider(), AppleMusicProvider(), YouTubeProvider(), TidalProvider(), DeezerProvider(),
        SubsonicProvider(), KoelProvider(), JellyfinProvider(), EmbyProvider(), PlexProvider(), LyrionProvider(), MopidyProvider(),
        LastFmProvider(), ListenBrainzProvider(), LocalFilesProvider, ChartsProvider, NeteaseProvider, QqMusicProvider, AnghamiProvider, GaanaProvider,
    )
    private val families = ServiceCatalog.families(services)
    private fun names(list: List<ServiceCatalog.Family>) = list.map { it.name }

    @Test fun everyServiceHasItsCategory() {
        assertEquals(Category.STREAMING, SpotifyProvider().category)
        assertEquals(Category.SERVER, SubsonicProvider().category)
        assertEquals(Category.LISTENING, LastFmProvider().category)
        assertEquals(Category.FILES, LocalFilesProvider.category)
        assertEquals(Category.RADIO, ChartsProvider.category)
        assertEquals(Category.REGIONAL, NeteaseProvider.category)
        // A service nobody listed yet lands in Streaming, never nowhere.
        assertEquals(Category.STREAMING, ServiceCatalog.info("brand-new").category)
    }

    @Test fun gamesIsNotOfferedWhileEmpty() {
        val cats = ServiceCatalog.categories(families)
        assertFalse(Category.GAMES in cats)
        assertFalse(Category.PODCASTS in cats)
        assertEquals(listOf(Category.STREAMING, Category.SERVER, Category.RADIO, Category.LISTENING, Category.REGIONAL, Category.FILES), cats)
    }

    @Test fun aGameConnectorJoinsGamesByOverridingItsCategory() {
        val game = object : MusicProvider by LocalFilesProvider {
            override val serviceId = "osu"
            override val displayName = "osu!"
            override val familyName = "osu!"
            override val category = Category.GAMES
        }
        val withGame = ServiceCatalog.families(services + game)
        assertTrue(Category.GAMES in ServiceCatalog.categories(withGame))
        assertEquals(listOf("osu!"), names(ServiceCatalog.sections(withGame, Category.GAMES, null, null).single().families))
    }

    @Test fun searchFindsOtherNames() {
        assertEquals(listOf("Subsonic / Navidrome"), names(ServiceCatalog.search(families, "Navidrome")))
        assertEquals(listOf("Plex"), names(ServiceCatalog.search(families, "plexamp")))
        assertEquals(listOf("NetEase Cloud Music"), names(ServiceCatalog.search(families, "网易")))
        for (server in listOf("Nextcloud Music", "Supysonic", "Astiga", "airsonic advanced", "Gonic", "Ampache", "LMS", "Funkwhale")) {
            assertEquals(server, listOf("Subsonic / Navidrome"), names(ServiceCatalog.search(families, server)))
        }
    }

    @Test fun searchPutsNamesThatStartWithItFirst() {
        // "mo": Mopidy starts with it, so it leads.
        val hits = names(ServiceCatalog.search(families, "mo"))
        assertEquals("Mopidy", hits.first())
        assertTrue(ServiceCatalog.search(families, "  ").size == families.size)
        assertTrue(ServiceCatalog.search(families, "zzzz").isEmpty())
    }

    @Test fun regionalServicesShowInAllOnlyWhereTheyAreAtHome() {
        fun regionalInAll(country: String?, lang: String?) =
            ServiceCatalog.sections(families, null, country, lang).firstOrNull { it.category == Category.REGIONAL }?.families?.let(::names)
        assertNull(regionalInAll("IT", "it"))
        assertEquals(listOf("NetEase Cloud Music", "QQ Music"), regionalInAll("CN", "zh"))
        // The language is enough: Arabic on a phone set to France.
        assertEquals(listOf("Anghami"), regionalInAll("FR", "ar"))
        assertEquals(listOf("Gaana"), regionalInAll("IN", "en"))
    }

    @Test fun theRegionalChipGroupsByArea() {
        val sections = ServiceCatalog.sections(families, Category.REGIONAL, "IN", "en")
        assertTrue(sections[0].suggested)
        assertEquals(listOf("Gaana"), names(sections[0].families))
        assertEquals(ServiceCatalog.Area.EAST_ASIA, sections[1].area)
        assertEquals(listOf("NetEase Cloud Music", "QQ Music"), names(sections[1].families))
        assertEquals(ServiceCatalog.Area.SOUTH_ASIA_MIDDLE_EAST, sections[2].area)
        assertEquals(listOf("Anghami"), names(sections[2].families))
    }

    @Test fun allShowsTheFirstFewOfEachCategoryWithShowAll() {
        val all = ServiceCatalog.sections(families, null, "IT", "it")
        val servers = all.first { it.category == Category.SERVER }
        assertEquals(ServiceCatalog.PREVIEW, servers.families.size)
        assertEquals(7, servers.total)
        assertTrue(servers.more)
        val listening = all.first { it.category == Category.LISTENING }
        assertFalse(listening.more)
        // A chip: the whole category.
        assertEquals(7, ServiceCatalog.sections(families, Category.SERVER, "IT", "it").single().families.size)
    }

    @Test fun aReadOnlyServiceCannotBeATarget() {
        assertEquals("chooser_cannot_target", ServiceCatalog.reason(ChartsProvider, ServiceCatalog.Role.TARGET))
        assertNull(ServiceCatalog.reason(ChartsProvider, ServiceCatalog.Role.SOURCE))
        assertNull(ServiceCatalog.reason(SpotifyProvider(), ServiceCatalog.Role.TARGET))
    }
}
