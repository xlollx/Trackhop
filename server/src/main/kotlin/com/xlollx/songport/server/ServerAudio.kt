package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.ytmbridge.Resample
import com.xlollx.songport.ytmbridge.Shazam
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * The song in a TikTok "original sound" on Docker and Desktop: the sound's MP3 downloaded, about
 * twelve seconds from its middle decoded (JLayer, in plain Java, nothing to install) and recognised
 * by [Shazam]. Off with the official-only build (Microsoft Store), like TikTok itself.
 */
object ServerAudio {
    private const val MAX_BYTES = 25 * 1024 * 1024
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    val recognizer: suspend (String) -> Track? = { url -> withContext(Dispatchers.IO) { recognize(url) } }

    fun recognize(url: String): Track? {
        require(url.startsWith("https://")) { "not an https address" }
        val bytes = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            .header("Referer", "https://www.tiktok.com/").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("the sound's file answered HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("the sound's file is empty")
            if (body.contentLength() > MAX_BYTES) throw IllegalStateException("the sound's file is too large")
            body.bytes()
        }
        val pcm = decodeMiddle(bytes, Shazam.SECONDS + 1)
        if (pcm.size < Shazam.SAMPLE_RATE * 3) return null
        val sig = Shazam.signature(Shazam.excerpt(pcm))
        val m = Shazam.recognize(sig)
        Log.add("shazam", "${pcm.size / Shazam.SAMPLE_RATE}s decoded, ${sig.peakCount} peaks: ${if (m != null) "recognised" else "no song found"}")
        return m?.let { Track(id = it.isrc ?: "shazam:${it.title}", title = it.title, artists = listOfNotNull(it.artist.ifBlank { null }), album = it.album.orEmpty(), isrc = it.isrc) }
    }

    /** [seconds] of an MP3 around its middle, as 16 kHz mono: the frames before it are skipped, not decoded. */
    fun decodeMiddle(mp3: ByteArray, seconds: Int): ShortArray {
        val stream = Bitstream(ByteArrayInputStream(mp3))
        val decoder = Decoder()
        try {
            val first = stream.readFrame() ?: return ShortArray(0)
            val totalMs = first.total_ms(mp3.size)
            val startMs = (totalMs / 2 - seconds * 500f).coerceAtLeast(0f)
            var at = 0f
            var header = first
            var rate = 0
            val out = ArrayList<Short>(seconds * 48_000)
            while (true) {
                if (at + header.ms_per_frame() >= startMs) {
                    val buf = decoder.decodeFrame(header, stream) as SampleBuffer
                    rate = buf.sampleFrequency
                    val ch = buf.channelCount
                    val s = buf.buffer
                    var k = 0
                    while (k + ch <= buf.bufferLength) { var sum = 0; for (c in 0 until ch) sum += s[k + c]; out += (sum / ch).toShort(); k += ch }
                    if (rate > 0 && out.size >= seconds * rate) break
                }
                at += header.ms_per_frame()
                stream.closeFrame()
                header = stream.readFrame() ?: break
            }
            return if (rate == 0) ShortArray(0) else Resample.to16k(out.toShortArray(), rate)
        } finally {
            runCatching { stream.close() }
        }
    }
}
