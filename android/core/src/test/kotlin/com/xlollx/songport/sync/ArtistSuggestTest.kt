package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.sync.ArtistAlerts.Followed
import com.xlollx.songport.sync.ArtistSuggest.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistSuggestTest {
    private val pd = "a".repeat(8) + "-" + "1".repeat(4) + "-" + "2".repeat(4) + "-" + "3".repeat(4) + "-" + "b".repeat(12)
    private val other = "c".repeat(8) + "-" + "1".repeat(4) + "-" + "2".repeat(4) + "-" + "3".repeat(4) + "-" + "d".repeat(12)

    private val answer = """{"artists":[
        {"id":"$pd","score":100,"name":"Parkway Drive","country":"AU","area":{"name":"Australia"},"disambiguation":"",
         "tags":[{"count":1,"name":"rock"},{"count":7,"name":"metalcore"}]},
        {"id":"$other","score":61,"name":"Parkway","disambiguation":"UK indie band","area":{"name":"United Kingdom"}},
        {"id":"not-an-id","score":40,"name":"  "}
    ]}"""

    @Test fun theSearchAnswerGivesTheNameTheIdAndWhatTellsItApart() {
        val list = ArtistSuggest.parse(parseJson(answer))
        assertEquals(listOf(Suggestion("Parkway Drive", pd, "metalcore · Australia"), Suggestion("Parkway", other, "UK indie band · United Kingdom")), list)
        assertTrue(ArtistSuggest.parse(parseJson("{}")).isEmpty())
    }

    @Test fun aNameHalfTypedIsAskedAsAPhraseOrAsABeginning() {
        assertEquals("\"parkway dri\"^2 OR (parkway AND dri*)", ArtistSuggest.query("  parkway   dri "))
        assertEquals("\"AC\\/DC\"^2 OR (ac\\/dc)", ArtistSuggest.query("AC/DC"))
        assertEquals("", ArtistSuggest.query("p"))
        assertEquals("", ArtistSuggest.query("  "))
    }

    @Test fun knownNamesComeByHowWellTheyStartWithWhatWasTyped() {
        val names = listOf("Imparkway", "Drive Parkway", "Parkway Drive", "parkway drive", "Muse", "Pärkway North")
        assertEquals(listOf("Parkway Drive", "Pärkway North", "Drive Parkway", "Imparkway"), ArtistSuggest.local(names, "PARK").map { it.name })
        assertTrue(ArtistSuggest.local(names, "park").all { it.known && it.mbid == null })
        assertEquals(2, ArtistSuggest.local(names, "park", limit = 2).size)
        assertTrue(ArtistSuggest.local(names, "p").isEmpty())
    }

    @Test fun eachArtistIsOfferedOnceHoweverItIsWritten() {
        val local = ArtistSuggest.local(listOf("parkway drive", "Parkway Avenue"), "parkway")
        val remote = ArtistSuggest.parse(parseJson(answer)) + Suggestion("PARKWAY DRIVE", other, "a tribute")
        val list = ArtistSuggest.merge("parkway", emptyList(), local, remote)
        // The known name takes MusicBrainz's spelling, id and detail; the name typed is itself an artist, so it comes first and is not offered again.
        assertEquals(listOf("Parkway", "Parkway Avenue", "Parkway Drive"), list.map { it.name })
        assertEquals(Suggestion("Parkway Drive", pd, "metalcore · Australia", known = true), list[2])
        assertFalse(list.any { it.typed })
    }

    @Test fun whatIsFollowedAlreadyIsMarkedAndTheTypedNameIsOfferedLastWhenNobodyHasIt() {
        val followed = listOf(Followed("parkway drive"))
        val list = ArtistSuggest.merge(" Parkway  dr ", followed, emptyList(), ArtistSuggest.parse(parseJson(answer)))
        assertEquals(listOf("Parkway Drive", "Parkway", "Parkway dr"), list.map { it.name })
        assertEquals(listOf(true, false, false), list.map { it.followed })
        assertEquals(listOf(false, false, true), list.map { it.typed })
        // The typed name followed already: marked, whatever its case.
        assertTrue(ArtistSuggest.merge("PARKWAY DRIVE", followed, emptyList(), emptyList()).single().let { it.typed && it.followed })
        assertTrue(ArtistSuggest.merge("p", followed, emptyList(), emptyList()).isEmpty())
        assertEquals(3, ArtistSuggest.merge("zz", emptyList(), emptyList(), (1..9).map { Suggestion("Band $it") }, limit = 2).size)
    }

    @Test fun theArtistsMetAreRememberedOnceAndTheNewestKept() {
        val env = ContractEnv()
        ArtistSuggest.note(env, listOf("Muse", " Architects ", "muse", ""))
        ArtistSuggest.note(env, listOf("MUSE", "Bring Me The Horizon"))
        assertEquals(listOf("Muse", "Architects", "Bring Me The Horizon"), ArtistSuggest.known(env))
        ArtistSuggest.note(env, (1..ArtistSuggest.MAX_KNOWN).map { "Band $it" })
        assertEquals(ArtistSuggest.MAX_KNOWN, ArtistSuggest.known(env).size)
        assertEquals("Band 1", ArtistSuggest.known(env).first())
    }

    @Test fun deezersPictureIsTheNamedArtistsAndNeverThePlaceholder() {
        val body = """{"data":[
            {"name":"Parkway Drive Tribute","nb_fan":9,"picture_medium":"https://e-cdns-images.dzcdn.net/images/artist/zzz/250x250-000000-80-0-0.jpg"},
            {"name":"Parkway Drive","nb_fan":5,"picture_medium":"https://e-cdns-images.dzcdn.net/images/artist/small/250x250-000000-80-0-0.jpg"},
            {"name":"PARKWAY DRIVE","nb_fan":900,"picture_medium":"https://e-cdns-images.dzcdn.net/images/artist/abc123/250x250-000000-80-0-0.jpg"},
            {"name":"Nobody","nb_fan":1,"picture_medium":"https://e-cdns-images.dzcdn.net/images/artist//250x250-000000-80-0-0.jpg"}
        ]}"""
        assertEquals("https://e-cdns-images.dzcdn.net/images/artist/abc123/250x250-000000-80-0-0.jpg", DeezerCatalog.pickArtistPicture(body, "parkway drive"))
        assertNull(DeezerCatalog.pickArtistPicture(body, "Nobody"))
        assertNull(DeezerCatalog.pickArtistPicture(body, "Muse"))
    }
}
