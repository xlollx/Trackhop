package com.xlollx.songport.server

/**
 * What a person may do on a server shared by several people (MultiUser.kt), set by the admin on the
 * Users page. The admin, and everyone on a server for one person, may do everything.
 */
object Permissions {
    /** Manages the users, as the admin does. */
    const val ADMIN = "admin"
    /** The unofficial connectors (cookies pasted from a browser, public links read as the sites do). */
    const val UNOFFICIAL = "unofficial"
    /** Radio syncs: the server listens to a station for hours, with its bandwidth. */
    const val RADIO = "radio"
    /** Folders on the server: backups copied to one. */
    const val FOLDERS = "folders"
    /** The API, its webhook, the notifications (they call other servers) and the command line. */
    const val API = "api"

    /** In the order the Users page lists them: the key, its name and what it allows. */
    val ALL = listOf(
        Triple(ADMIN, "Co-admin", "Adds and removes people, sets their passwords and permissions."),
        Triple(UNOFFICIAL, "Unofficial connectors", "YouTube Music, Spotify and Deezer with browser cookies; NetEase, QQ Music and SoundCloud links. The services may not like them: an account can be limited."),
        Triple(RADIO, "Radio syncs", "The server listens to a station for hours: it uses the server's bandwidth."),
        Triple(FOLDERS, "Folders on the server", "Backups copied to a folder of the server."),
        Triple(API, "API and command line", "A token for scripts and Home Assistant, the webhook, notifications, the trackhop command."),
    )

    /** What a person added without choosing gets: everything but managing the others. */
    val DEFAULT: Set<String> = setOf(UNOFFICIAL, RADIO, FOLDERS, API)

    fun parse(s: String?): Set<String> = s.orEmpty().split(',').map { it.trim() }.filter { k -> ALL.any { it.first == k } }.toSet()
    fun format(p: Set<String>): String = ALL.map { it.first }.filter { it in p }.joinToString(",")
}
