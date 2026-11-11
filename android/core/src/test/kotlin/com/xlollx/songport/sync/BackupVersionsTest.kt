package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.LocalFilesProvider.BackupOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dated backup copies Vanished songs compares: one account's copies under one name, an empty
 * listing never taking the place of a good copy, a second copy of the same minute in its group.
 */
class BackupVersionsTest {
    private val env = ContractEnv()
    private fun songs(n: Int, prefix: String = "Song") = (1..n).map { Track(id = "$prefix$it", title = "$prefix $it", artists = listOf("Artist $it")) }
    private val minute = 1_762_889_000_000L

    private fun versions(base: String) = LocalFilesProvider.entries(env).filter { it.base == base && it.stamped }.sortedByDescending { it.writtenAt }

    @Test fun twoAccountsOfAServiceKeepTheirCopiesApart() = runBlocking {
        val main = MemoryService("YouTube Music").apply { lists["p"] = songs(63).toMutableList(); names["p"] = "Saved albums" }
        val second = object : MemoryService("YouTube Music") { override val slot: String get() = "2" }
            .apply { lists["p"] = songs(5, prefix = "Other").toMutableList(); names["p"] = "Saved albums" }
        assertEquals("YouTube Music", LibraryTools.backupService(main))
        assertEquals("YouTube Music (2)", LibraryTools.backupService(second))

        LibraryTools.backupAll(env, main)
        LibraryTools.backupAll(env, second)

        val mine = versions("YouTube Music - Saved albums")
        assertEquals(1, mine.size)
        assertEquals(63, mine.single().trackCount)
        assertEquals(5, versions("YouTube Music (2) - Saved albums").single().trackCount)
    }

    @Test fun anEmptyListingDoesNotReplaceAGoodCopy() {
        assertEquals(BackupOutcome.WRITTEN, LocalFilesProvider.writeBackup(env, "Svc", "Saved albums", songs(63), emptySet(), minute))
        assertEquals(BackupOutcome.KEPT, LocalFilesProvider.writeBackup(env, "Svc", "Saved albums", emptyList(), emptySet(), minute + 3_600_000))
        val left = versions("Svc - Saved albums")
        assertEquals(1, left.size)
        assertEquals(63, left.single().trackCount)
        // A playlist that never had anything is written as it is.
        assertEquals(BackupOutcome.WRITTEN, LocalFilesProvider.writeBackup(env, "Svc", "Empty", emptyList(), emptySet(), minute))
    }

    @Test fun aSecondCopyOfTheSameMinuteJoinsItsGroupAndComesAfter() {
        LocalFilesProvider.writeBackup(env, "Svc", "Mix", songs(10), emptySet(), minute)
        LocalFilesProvider.writeBackup(env, "Svc", "Mix", songs(11), emptySet(), minute + 20_000)
        val mix = versions("Svc - Mix")
        assertEquals(2, mix.size)
        assertTrue(mix.all { it.service == "Svc" && it.title == "Mix" })
        // Newest first: the second one written, though both carry the same minute.
        assertEquals(11, mix[0].trackCount)
        assertTrue(mix[0].id.endsWith(" (2)"))
    }
}
