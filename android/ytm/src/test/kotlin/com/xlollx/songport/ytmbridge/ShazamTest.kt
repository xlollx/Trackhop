package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamTest {
    /** Two tones changing every half second: enough peaks to sign, the same every time. */
    private fun tones(seconds: Int): ShortArray = ShortArray(seconds * Shazam.SAMPLE_RATE) { i ->
        val f = if ((i / 8000) % 2 == 0) 440.0 else 1760.0
        (sin(2 * PI * f * i / Shazam.SAMPLE_RATE) * 12000 + sin(2 * PI * 3000.0 * i / Shazam.SAMPLE_RATE) * 4000).toInt().toShort()
    }

    @Test fun theSignatureHasShazamsHeaderAndCrc() {
        val sig = Shazam.signature(Shazam.excerpt(tones(20)))
        assertTrue(sig.peakCount > 0)
        val bytes = sig.encode()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), b.getInt(0))
        assertEquals(bytes.size - 48, b.getInt(8))
        assertEquals(0x94119C00.toInt(), b.getInt(12))
        assertEquals(3 shl 27, b.getInt(28))
        assertEquals(12 * 16000 + 3840, b.getInt(40))
        assertEquals(0x40000000, b.getInt(48))
        assertEquals(bytes.size - 48, b.getInt(52))
        assertEquals(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt(), b.getInt(4))
        assertEquals(0, bytes.size % 4)
        assertTrue(sig.uri().startsWith("data:audio/vnd.shazam.sig;base64,"))
    }

    @Test fun theExcerptIsTheMiddleTwelveSecondsOrPadded() {
        val long = ShortArray(30 * 16000) { (it / 16000).toShort() }
        val e = Shazam.excerpt(long)
        assertEquals(12 * 16000, e.size)
        assertEquals(9.toShort(), e[0])
        assertEquals(12 * 16000, Shazam.excerpt(ShortArray(100)).size)
    }

    @Test fun theSameAudioGivesTheSameSignature() {
        assertTrue(Shazam.signature(Shazam.excerpt(tones(20))).encode().contentEquals(Shazam.signature(Shazam.excerpt(tones(20))).encode()))
    }

    @Test fun anAnswerWithATrackIsAMatchAndOneWithoutIsNot() {
        val m = Shazam.parse(parseJson("""{"matches":[{"id":"1"}],"track":{"title":"Espresso","subtitle":"Sabrina Carpenter","isrc":"USUM72401994",
            "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Short n' Sweet"},{"title":"Released","text":"2024"}]}]}}"""))
        assertNotNull(m)
        assertEquals("Espresso", m!!.title)
        assertEquals("Sabrina Carpenter", m.artist)
        assertEquals("Short n' Sweet", m.album)
        assertEquals("USUM72401994", m.isrc)
        assertNull(Shazam.parse(parseJson("""{"matches":[],"tagid":"x"}""")))
    }

    /** Live, by hand: SHAZAM_PCM44=<44.1 kHz mono s16le> goes through [Resample] first. */
    @Test fun liveRecognitionAfterResampling() {
        val path = System.getenv("SHAZAM_PCM44") ?: ""
        assumeTrue(path.isNotEmpty())
        val raw = File(path).readBytes()
        val pcm = ShortArray(raw.size / 2).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val sig = Shazam.signature(Shazam.excerpt(Resample.to16k(pcm, 44100)))
        println("peaks44 ${sig.peakCount}")
        println("match44 ${Shazam.recognize(sig)}")
    }

    @Test fun resamplingKeepsTheDurationAndALowTone() {
        val rate = 44100
        val pcm = ShortArray(rate * 2) { (sin(2 * PI * 440.0 * it / rate) * 10000).toInt().toShort() }
        val out = Resample.to16k(pcm, rate)
        assertEquals(32000.0, out.size.toDouble(), 2.0)
        assertTrue(out.maxOf { it.toInt() } in 9000..10500)
    }

    /** Live, by hand: SHAZAM_PCM=<16 kHz mono s16le file> asks the real Shazam. */
    @Test fun liveRecognition() {
        val path = System.getenv("SHAZAM_PCM") ?: ""
        assumeTrue(path.isNotEmpty())
        val raw = File(path).readBytes()
        val pcm = ShortArray(raw.size / 2).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val sig = Shazam.signature(Shazam.excerpt(pcm))
        println("peaks ${sig.peakCount}")
        println("match ${Shazam.recognize(sig)}")
    }
}
