package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Playlist files written in a legacy encoding by apps and players in China, Japan, Korea and Russia. */
class TextCharsetsTest {
    private fun m3u(vararg names: String) = "#EXTM3U\n" + names.joinToString("") { "#EXTINF:200,$it\n$it.mp3\n" }

    private fun roundTrip(charset: String, text: String) {
        assertEquals(charset, text, PlaylistFiles.decode(text.toByteArray(charset(charset))))
        // Read as it comes, the same.
        assertEquals(charset, text, PlaylistFiles.reader(text.toByteArray(charset(charset)).inputStream()).readText())
    }

    @Test fun chinese() = roundTrip("GB18030", m3u("周杰伦 - 晴天", "王菲 - 红豆", "陈奕迅 - 十年"))
    @Test fun japanese() = roundTrip("Shift_JIS", m3u("宇多田ヒカル - First Love", "米津玄師 - Lemon", "あいみょん - マリーゴールド"))
    @Test fun korean() = roundTrip("EUC-KR", m3u("아이유 - 좋은 날", "방탄소년단 - 봄날", "빅뱅 - 거짓말"))
    @Test fun russian() = roundTrip("windows-1251", m3u("Кино - Группа крови", "Земфира - Хочешь?", "Сплин - Выхода нет"))
    @Test fun western() = roundTrip("windows-1252", m3u("Beyoncé - Halo", "Sigur Rós - Hoppípolla", "Café Tacvba - Eres"))
    @Test fun utf8() = roundTrip("UTF-8", m3u("周杰伦 - 晴天", "Кино - Группа крови", "Beyoncé - Halo"))

    @Test fun aLongAsciiStartThenRussian() {
        // The legacy bytes come after the reader's first buffer: the guess is made from where they start.
        val text = m3u(*Array(4000) { "Artist $it - Song $it" }) + m3u("Кино - Группа крови", "Земфира - Хочешь?")
        assertEquals(text, PlaylistFiles.reader(text.toByteArray(charset("windows-1251")).inputStream()).readText())
    }
}
