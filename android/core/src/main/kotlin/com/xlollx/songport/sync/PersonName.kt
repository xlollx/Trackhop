package com.xlollx.songport.sync

/**
 * A service account's name as a person would sign with it, or null when it is an id rather than a
 * name: an account made through Facebook or Google often has a number (Spotify: "1234567890") or a
 * random code ("31abcdefghijklmnopqrstuvwxyz") as its user name, and some services give an e-mail.
 * Those never go on a poster.
 */
object PersonName {
    private val digits = Regex("""[\d\s._-]+""")
    private val code = Regex("""[a-z0-9]{20,}""")

    fun of(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || '@' in s) return null
        if (s.matches(digits) || s.matches(code)) return null
        return s
    }
}
