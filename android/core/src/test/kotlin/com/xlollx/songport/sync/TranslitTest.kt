package com.xlollx.songport.sync

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A song listed in another writing system on one service and in Latin letters on the other. */
class TranslitTest {
    @Before fun on() { Features.build = true }
    @After fun off() { Features.build = false; Providers.testDoubles = emptyList() }

    private fun t(title: String, artist: String, ms: Long = 240_000, id: String = title) = Track(id, title, listOf(artist), "", ms)
    private val accept = MatchPolicy().acceptThreshold

    @Test fun lettersInLatin() {
        assertEquals("gruppa krovi", Translit.latin("Группа крови"))
        assertEquals("viktor tsoy", Translit.latin("Виктор Цой"))
        assertEquals("eleftheria", Translit.latin("Ελευθερία"))
        assertEquals("giorgos dalaras", Translit.latin("Γιώργος Νταλάρας"))
        assertEquals("sakuranbo", Translit.latin("さくらんぼ"))
        assertEquals("kyari pamyu pamyu", Translit.latin("きゃりー ぱみゅ ぱみゅ"))
        assertEquals("chotto matte", Translit.latin("ちょっと まって"))
        assertEquals("fasuto", Translit.latin("ファースト"))
        assertEquals("gangnam", Translit.latin("강남"))
        assertEquals("Kino (Live)", Translit.latin("Kino (Live)"))
        // No reading without a dictionary.
        assertNull(Translit.latin("宇多田ヒカル"))
        assertNull(Translit.latin("เพลง"))
        assertEquals(Translit.skeleton("omer adam"), Translit.skeleton(Translit.latin("עומר אדם")!!))
        assertEquals(Translit.skeleton("fairouz"), Translit.skeleton(Translit.latin("فيروز")!!))
        assertFalse(Translit.foreign("Beyoncé – Déjà Vu, Việt Nam"))
    }

    @Test fun theSameSongInTwoScripts() {
        val pairs = listOf(
            t("Группа крови", "Кино") to t("Gruppa krovi", "Kino"),
            t("Звезда по имени Солнце (Remastered 2011)", "Кино") to t("Zvezda po imeni Solntse", "Kino"),
            t("Gruppa krovi", "Кино") to t("Gruppa krovi", "Kino"),
            t("Ελευθερία", "Γιώργος Νταλάρας") to t("Eleftheria", "Giorgos Dalaras"),
            t("さよなら", "ゆず") to t("Sayonara", "Yuzu"),
            t("소나기", "이하이") to t("Sonagi", "Ihai"),
        )
        for ((a, b) in pairs) {
            assertTrue("$a / $b: ${Matcher.score(a, b)}", Matcher.score(a, b) >= Matcher.REVIEW_THRESHOLD)
            assertTrue("$b / $a", Matcher.score(b, a) >= Matcher.REVIEW_THRESHOLD)
        }
    }

    @Test fun hebrewAndArabicAreAcceptedButToBeChecked() {
        for ((a, b) in listOf(
            t("שלום עליכם", "עומר אדם") to t("Shalom Aleichem", "Omer Adam"),
            t("تملي معاك", "عمرو دياب") to t("Tamally Maak", "Amr Diab"),
        )) {
            val s = Matcher.score(a, b)
            assertTrue("$a: $s", s >= accept && s < Matcher.REVIEW_THRESHOLD)
        }
        // The title in Latin letters on both sides, the artist told by consonants alone: checked too.
        for ((a, b) in listOf(
            t("Shalom Aleichem", "עומר אדם") to t("Shalom Aleichem", "Omer Adam"),
            t("Tamally Maak", "عمرو دياب") to t("Tamally Maak", "Amr Diab"),
        )) {
            val s = Matcher.score(a, b)
            assertTrue("$a: $s", s >= accept && s < Matcher.REVIEW_THRESHOLD)
            assertTrue(Matcher.score(b, a) < Matcher.REVIEW_THRESHOLD)
        }
        // Other consonants: another name.
        assertTrue(Matcher.score(t("שלום עליכם", "עומר אדם"), t("Shalom Aleichem", "Eyal Golan")) < accept)
    }

    @Test fun aSharedTitleIsNotTheSameSong() {
        val src = t("Группа крови", "Кино")
        // Another artist; the same one but far longer; an artist that cannot be read.
        assertTrue(Matcher.score(src, t("Gruppa krovi", "Some Tribute Band")) < accept)
        assertTrue(Matcher.score(src, t("Gruppa krovi", "Kino", 400_000)) < accept)
        assertTrue(Matcher.score(t("First Love", "宇多田ヒカル"), t("First Love", "Hikaru Utada")) < accept)
        // The same artist, another song.
        assertTrue(Matcher.score(src, t("Pachka sigaret", "Kino")) < accept)
        assertNull(Matcher.bestScored(src, listOf(t("Gruppa krovi", "Tribute"), t("Pachka sigaret", "Kino")), accept))
        // A live take stays a live take.
        assertTrue(Matcher.score(src, t("Gruppa krovi (Live)", "Kino")) < accept)
    }

    @Test fun alreadyInThePlaylistUnderItsLatinName() {
        val index = Matcher.TrackIndex(listOf(t("Gruppa krovi", "Kino", id = "d"), t("Pachka sigaret", "Kino", id = "e")))
        assertEquals("d", index.best(t("Группа крови", "Кино"))?.id)
        assertEquals("x", Matcher.TrackIndex(listOf(t("Группа крови", "Кино", id = "x"))).best(t("Gruppa krovi", "Kino"))?.id)
    }

    @Test fun nothingChangesWithoutTheSwitchOrBetweenLatinNames() {
        val latin = listOf(
            t("Déjà Vu (feat. Jay-Z)", "Beyoncé") to t("Deja Vu", "Beyonce", 243_000),
            t("Oxygene, Pt. 4", "Jean-Michel Jarre") to t("Oxygène (Part IV)", "Jean Michel Jarre"),
            t("Hello", "Adele") to t("Hello", "Lionel Richie", 250_000),
            t("Wonderwall - Remastered", "Oasis") to t("Wonderwall (Live)", "Oasis", 260_000),
        )
        val on = latin.map { (a, b) -> Matcher.score(a, b) }
        Features.build = false
        assertEquals(on, latin.map { (a, b) -> Matcher.score(a, b) })
        assertEquals(2, Matcher.REVISION)
        // Off, two scripts score as they did: not found.
        assertTrue(Matcher.score(t("Группа крови", "Кино"), t("Gruppa krovi", "Kino")) < accept)
        assertNull(Matcher.TrackIndex(listOf(t("Gruppa krovi", "Kino"))).best(t("Группа крови", "Кино")))
    }

    /** A whole sync: the source in Cyrillic, a target whose search finds a title only as it lists it. */
    private fun sync(catalogue: List<Track>): Pair<List<String>, Int> = runBlocking {
        val env = ContractEnv()
        val src = MemoryService("msrc"); val dst = MemoryService("mdst")
        src.lists["p"] = mutableListOf(t("Группа крови", "Кино", id = "s1"))
        dst.catalogue += catalogue
        dst.lists["t"] = ArrayList()
        Providers.testDoubles = listOf(src, dst)
        val job = SyncJob(id = "j", name = "j", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("mdst", "t", "t")).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        dst.lists["t"]!!.map { it.id } to r.unmatchedTracks.size
    }

    @Test fun theSyncSearchesTheLatinName() {
        assertEquals(listOf("right") to 0, sync(listOf(t("Gruppa krovi", "Kino", 241_000, id = "right"), t("Gruppa krovi", "Some Tribute Band", id = "cover"))))
    }

    @Test fun theSyncTakesNoOtherSongOfThatName() {
        assertEquals(emptyList<String>() to 1, sync(listOf(t("Gruppa krovi", "Some Tribute Band", id = "cover"), t("Gruppa krovi", "Kino", 400_000, id = "long"))))
    }

    @Test fun theSyncIsAsBeforeWithoutTheSwitch() {
        Features.build = false
        assertEquals(emptyList<String>() to 1, sync(listOf(t("Gruppa krovi", "Kino", id = "right"))))
    }
}
