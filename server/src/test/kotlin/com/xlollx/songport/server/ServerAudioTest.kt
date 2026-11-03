package com.xlollx.songport.server

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ServerAudioTest {
    /** By hand: TIKTOK_MP3=<a sound's mp3> decodes its middle and asks the real Shazam. */
    @Test fun liveMp3() {
        val path = System.getenv("TIKTOK_MP3") ?: ""
        assumeTrue(path.isNotEmpty())
        val pcm = ServerAudio.decodeMiddle(File(path).readBytes(), 13)
        println("decoded ${pcm.size / 16000}s")
        assertTrue(pcm.size >= 12 * 16000)
        val sig = com.xlollx.songport.ytmbridge.Shazam.signature(com.xlollx.songport.ytmbridge.Shazam.excerpt(pcm))
        println("server match ${com.xlollx.songport.ytmbridge.Shazam.recognize(sig)}")
    }
}
