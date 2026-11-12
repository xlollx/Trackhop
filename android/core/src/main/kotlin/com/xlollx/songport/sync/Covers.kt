package com.xlollx.songport.sync

import com.xlollx.songport.net.Http
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * A playlist cover fetched and made into what every service that takes one accepts: a square JPEG
 * of [MIN_SIDE] to [MAX_SIDE] px, at most [MAX_BYTES] bytes. YouTube Music wants at least 360 px,
 * Spotify's Web API at most 256 KB once base64-encoded; the others take anything that size.
 */
object Covers {
    const val MIN_SIDE = 360
    const val MAX_SIDE = 640
    /** 256 KB once base64-encoded (four bytes for every three), Spotify's Web API limit. */
    const val MAX_BYTES = 190_000

    suspend fun fetchJpeg(env: Env, url: String): ByteArray? = withContext(Dispatchers.IO) {
        val raw = Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.bytes() ?: return@withContext null
        }
        env.squareJpeg(raw, MIN_SIDE, MAX_SIDE, MAX_BYTES)
    }

    /** The square cut from an image: [x], [y] and [size] in the image, drawn at [side] px. */
    data class Square(val x: Int, val y: Int, val size: Int, val side: Int)

    /**
     * The centre square of a [width] x [height] image, drawn at its own size kept within
     * [minSide]..[maxSide] (a small image is enlarged, a large one reduced).
     */
    fun square(width: Int, height: Int, minSide: Int, maxSide: Int): Square {
        val size = minOf(width, height).coerceAtLeast(1)
        return Square((width - size) / 2, (height - size) / 2, size, size.coerceIn(minSide, maxSide.coerceAtLeast(minSide)))
    }
}
