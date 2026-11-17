package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class StorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun tokensSurviveARestartAndStayPrivate() {
        val file = File(tmp.root, "tokens.json")
        JsonTokenVault(file).set("spotify", Tokens(accessToken = "a", refreshToken = "r", userName = "me"))
        val again = JsonTokenVault(file)
        assertEquals("me", again.get("spotify")?.userName)
        // Owner-only where the file system has POSIX permissions (Windows: the user's own folder decides).
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertFalse(PosixFilePermission.GROUP_READ in perms || PosixFilePermission.OTHERS_READ in perms)
        }
        again.clear("spotify")
        assertNull(JsonTokenVault(file).get("spotify"))
    }

    @Test fun theCopyOfADamagedFileIsPrivateToo() {
        org.junit.Assume.assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val file = File(tmp.root, "tokens.json")
        file.writeText("{\"spotify\":{\"accessToken\":\"a")
        Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        val keep = keepDamaged(file)!!
        assertEquals(file.readText(), keep.readText())
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(keep.toPath())))
    }

    @Test fun keyValuesPersist() {
        val file = File(tmp.root, "kv/isrc.json")
        JsonKeyValues(file)["a"] = "1"
        val again = JsonKeyValues(file)
        assertEquals("1", again["a"])
        assertEquals(1, again.size)
        again.clear()
        assertEquals(0, JsonKeyValues(file).size)
    }

    @Test fun pkceChallengeIsUnpaddedBase64UrlOfSha256() {
        // Computed independently (Python: urlsafe_b64encode(sha256(verifier)) without padding).
        assertEquals("DkvWAcd1jIwGCnyAaagTf6Hrgf4j2Qcmk-anNixzN7M", Auth.challenge("dBjftJeZ4CVP-mJ92K9pdjHnfJgMjEV3t9n0PRIhoV0"))
    }

    @Test fun jobsAreDueByTheirSchedule() {
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://localhost:8080")
        val runner = Runner(env)
        val now = 10_000_000_000L
        val job = SyncJob("j", "J", PlaylistRef("a", "1"), PlaylistRef("b", "2"), schedule = Schedule.HOURLY, lastRunEpoch = now - 30 * 60_000)
        assertFalse(runner.isDue(job, now))
        assertTrue(runner.isDue(job.copy(lastRunEpoch = now - 61 * 60_000), now))
        assertFalse(runner.isDue(job.copy(schedule = Schedule.MANUAL, lastRunEpoch = 0), now))
        assertFalse(runner.isDue(job.copy(enabled = false, lastRunEpoch = 0), now))
        assertEquals("http://localhost:8080/callback", env.redirectUri)
    }
}
