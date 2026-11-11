package com.xlollx.songport.providers

/**
 * The plain name of the service behind a connector, for anything that leaves the app (a card, a
 * poster's caption, a shared text): "Spotify", never "Spotify (web)", "Spotify (plugin)" or the
 * name the user gave their connector. Inside the app the connector's own label stays, there it
 * tells two routes or two accounts apart.
 */
object ServiceBrand {
    private val KNOWN = mapOf(
        "spotify" to "Spotify", "spotify_bridge" to "Spotify",
        "apple" to "Apple Music", "apple_bridge" to "Apple Music",
        "ytm" to "YouTube Music", "youtube" to "YouTube Music",
        "deezer" to "Deezer", "deezer_bridge" to "Deezer",
        "tidal" to "TIDAL", "amazon" to "Amazon Music", "soundcloud" to "SoundCloud",
        "anghami" to "Anghami", "anghami_web" to "Anghami",
        "qobuz_web" to "Qobuz", "liveone_web" to "LiveOne",
    )

    /** A name without the route in brackets or an account's number at its end: "Spotify (web) @2" → "Spotify". */
    fun plain(name: String): String {
        var s = name.trim()
        while (true) {
            val cut = s.replace(Regex("""\s*(\([^()]*\)|[@#]\s*\d+)\s*$"""), "").trim()
            if (cut.isEmpty() || cut == s) break
            s = cut
        }
        return s
    }

    /** The service's name: the known one of [serviceId], else [name] (a family or display name) made [plain]. */
    fun of(serviceId: String, name: String): String = KNOWN[serviceId] ?: plain(name)
}

/** The service's plain name, for cards and shared texts ([ServiceBrand]). */
val MusicProvider.brand: String get() = ServiceBrand.of(serviceId, familyName)
