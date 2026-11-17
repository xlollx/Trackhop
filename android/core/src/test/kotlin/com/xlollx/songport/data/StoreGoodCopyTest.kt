package com.xlollx.songport.data

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The last good copy of the state (store.json.bak): written when a start reads the state well and,
 * not too often, when it is saved well. A start that finds the state unreadable begins empty, keeps
 * the copy as it is and offers it; it comes back only when the person asks, never by itself.
 */
class StoreGoodCopyTest {
    private val every = FileStore.GOOD_COPY_EVERY_MS
    private fun dir() = Files.createTempDirectory("goodcopy").toFile()
    private fun sync(id: String) = SyncJob(id = id, name = id, source = PlaylistRef("a", id), target = PlaylistRef("b"))
    private fun bak(d: File) = File(d, FileStore.GOOD_COPY)

    @Before fun setUp() { FileStore.GOOD_COPY_EVERY_MS = every }
    @After fun tearDown() { FileStore.GOOD_COPY_EVERY_MS = every }

    /** A folder whose state holds the syncs "one" and "two", read well by a start, then cut short on the disk. */
    private fun damagedAfterAGoodStart(): File {
        val d = dir()
        FileStore(d).apply { upsertJob(sync("one")); upsertJob(sync("two")); flush() }
        // The next start reads it well: that state becomes the last good copy.
        assertEquals(listOf("one", "two"), FileStore(d).data.jobs.map { it.id })
        assertTrue(bak(d).exists())
        File(d, "store.json").writeText("""{"jobs":[{"id":"one","na""")
        return d
    }

    @Test fun aGoodStartKeepsACopyAndSavesRenewItNoMoreThanOnceInAWhile() {
        val d = dir()
        val first = FileStore(d)
        assertFalse("nothing to copy at a first start", bak(d).exists())
        first.upsertJob(sync("one")); first.flush()
        assertTrue("the first state saved well is kept too", bak(d).readText().contains("\"one\""))
        first.upsertJob(sync("two")); first.flush()
        assertFalse("not again a moment later", bak(d).readText().contains("\"two\""))
        FileStore.GOOD_COPY_EVERY_MS = 0
        first.upsertJob(sync("three")); first.flush()
        assertTrue(bak(d).readText().contains("\"three\""))
        assertNull(first.notice.value)
        assertEquals("one copy, written whole", listOf("store.json", "store.json.bak"), d.list()!!.sorted())
    }

    @Test fun aDamagedStateStartsEmptyAndOffersTheCopyWithoutEverTakingItByItself() {
        val d = damagedAfterAGoodStart()
        val store = FileStore(d)
        assertTrue("never restored by itself", store.data.jobs.isEmpty())
        assertNotNull(store.damagedCopy)
        val notice = store.notice.value
        assertNotNull(notice)
        assertTrue(notice!!.damaged)
        assertNotNull(notice.goodCopyAt)

        // The empty start is saved, again and again: the copy on offer stays the good one.
        FileStore.GOOD_COPY_EVERY_MS = 0
        store.upsertJob(sync("made after")); store.flush()
        assertTrue(bak(d).readText().contains("\"two\""))
        assertFalse(bak(d).readText().contains("made after"))

        // The app is closed and opened: the state now reads well, and the copy is still on offer, untouched.
        val later = FileStore(d)
        assertEquals(listOf("made after"), later.data.jobs.map { it.id })
        assertNull(later.damagedCopy)
        assertNotNull("still offered until the person decides", later.notice.value?.goodCopyAt)
        assertTrue(bak(d).readText().contains("\"two\""))

        // The person asks for it.
        assertTrue(later.restoreGoodCopy())
        assertEquals(listOf("one", "two"), later.data.jobs.map { it.id })
        assertNull(later.notice.value)
        assertTrue("what was there before the restore is kept beside", File(d, FileStore.BEFORE_RESTORE).readText().contains("made after"))
        // And it is what the next start reads, with nothing more to say.
        val after = FileStore(d)
        assertEquals(listOf("one", "two"), after.data.jobs.map { it.id })
        assertNull(after.notice.value)
    }

    @Test fun goingOnWithoutTheCopyEndsTheOfferAndTheCopyIsRenewedAgain() {
        val d = damagedAfterAGoodStart()
        val store = FileStore(d)
        store.dismissNotice()
        assertNull(store.notice.value)
        assertTrue(store.data.jobs.isEmpty())
        FileStore.GOOD_COPY_EVERY_MS = 0
        store.upsertJob(sync("fresh")); store.flush()
        assertTrue(bak(d).readText().contains("fresh"))
        assertNull(FileStore(d).notice.value)
    }

    @Test fun aCopyThatCannotBeReadIsNotPutInPlace() {
        val d = damagedAfterAGoodStart()
        bak(d).writeText("""{"jobs":[{"id":""")
        val store = FileStore(d)
        store.upsertJob(sync("kept"))
        assertFalse(store.restoreGoodCopy())
        assertEquals(listOf("kept"), store.data.jobs.map { it.id })
    }

    @Test fun aDamagedStateWithNoCopySaysSoWithoutAnOffer() {
        val d = dir()
        File(d, "store.json").writeText("{ not json")
        val store = FileStore(d)
        assertEquals(FileStore.Notice(damaged = true, goodCopyAt = null), store.notice.value)
        assertFalse(store.restoreGoodCopy())
    }

    @Test fun aRestoreThatCannotBeSavedPutsTheStateBackAsItWas() {
        val d = damagedAfterAGoodStart()
        val store = FileStore(d)
        store.upsertJob(sync("made after")); store.flush()
        store.failWrites = true
        assertFalse(store.restoreGoodCopy())
        assertEquals("the state shown is the one on the disk", listOf("made after"), store.data.jobs.map { it.id })
        assertNotNull("still on offer", store.notice.value?.goodCopyAt)
        store.failWrites = false
        assertTrue(store.restoreGoodCopy())
        assertEquals(listOf("one", "two"), store.data.jobs.map { it.id })
    }
}
