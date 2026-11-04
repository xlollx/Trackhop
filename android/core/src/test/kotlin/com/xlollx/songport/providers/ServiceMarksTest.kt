package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android's path parsers (Compose and androidx.core) do not read arc flags written together
 * ("a9.23 9.23 0 00-.24-2.19"), as Simple Icons ships them: Apple Music's mark came out broken.
 * Every arc in a mark must carry its seven numbers apart.
 */
class ServiceMarksTest {
    private val ids = listOf("spotify", "apple", "ytm", "youtube", "tidal", "plex", "jellyfin", "emby", "deezer", "lastfm", "soundcloud", "netease")
    private val number = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

    @Test fun everyArcHasItsSevenNumbersApart() {
        for (id in ids + "shazam") {
            val d = if (id == "shazam") ServiceMarks.SHAZAM else ServiceMarks.pathData(id)!!
            Regex("""[aA]([^a-zA-Z]*)""").findAll(d).forEach { arc ->
                val count = number.findAll(arc.groupValues[1]).count()
                assertEquals("$id: arc \"${arc.value.take(40)}\" has compact flags", 0, count % 7)
            }
        }
    }
}
