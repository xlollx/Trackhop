package com.xlollx.songport.providers

import java.text.Normalizer

/**
 * "Which service?": the categories a service belongs to, the other names it goes by, where it is
 * at home, and how the chooser (app and server) orders, filters and searches them. Pure logic, no UI.
 *
 * To put a service in a category, either add a line to [INFO] or override [MusicProvider.category]
 * (and [MusicProvider.aliases]) in the provider: a game connector is `override val category get() =
 * ServiceCatalog.Category.GAMES` and nothing else. A category with no member is not offered.
 */
object ServiceCatalog {

    /** The chips, in their order. [stringName] names the text (app resources, read by the server too). */
    enum class Category(val key: String, val stringName: String) {
        STREAMING("streaming", "services_group_streaming"),
        SERVER("server", "services_group_servers"),
        GAMES("games", "chooser_cat_games"),
        RADIO("radio", "chooser_cat_radio"),
        PODCASTS("podcasts", "chooser_cat_podcasts"),
        LISTENING("listening", "chooser_cat_listening"),
        REGIONAL("regional", "chooser_cat_regional"),
        FILES("files", "chooser_cat_files");

        companion object {
            fun of(key: String?): Category? = entries.firstOrNull { it.key == key }
        }
    }

    /** Where a regional service is at home: the groups under the Regional chip, in this order. */
    enum class Area(val stringName: String) {
        EAST_ASIA("chooser_area_east_asia"),
        SOUTH_ASIA_MIDDLE_EAST("chooser_area_india_middle_east"),
        AFRICA("chooser_area_africa"),
        EUROPE("chooser_area_europe"),
        AMERICAS("chooser_area_americas"),
    }

    /** What the chooser knows about a service besides the provider itself. */
    class Info(
        val category: Category,
        /** Other names it is known by: "Navidrome" finds Subsonic, "Plexamp" Plex, "网易" NetEase. */
        val aliases: List<String> = emptyList(),
        /** ISO country codes where it is at home (regional services). */
        val countries: Set<String> = emptySet(),
        /** Languages (ISO 639-1) whose speakers are likely to use it. */
        val languages: Set<String> = emptySet(),
        val area: Area? = null,
        /** A short mark of where, shown at the end of its row ("CN", "IN", "MENA"). */
        val tag: String? = null,
    )

    private val MENA = setOf("AE", "SA", "EG", "LB", "JO", "KW", "QA", "BH", "OM", "IQ", "MA", "DZ", "TN", "LY", "PS", "SY", "YE", "SD")
    private val SUBSONIC_NAMES = listOf(
        "Navidrome", "Gonic", "Airsonic", "Airsonic-Advanced", "Ampache", "Nextcloud Music", "Supysonic",
        "Astiga", "LMS", "Lightweight Music Server", "Funkwhale", "OpenSubsonic",
    )
    private val CHINA = setOf("CN", "HK", "TW", "MO")
    private val INDIA_LANGS = setOf("hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "ur")

    /** By service id (the account's [MusicProvider.serviceId]). A service not listed is Streaming. */
    private val INFO: Map<String, Info> = mapOf(
        "spotify" to Info(Category.STREAMING),
        "spotify_bridge" to Info(Category.STREAMING),
        "apple" to Info(Category.STREAMING, listOf("iTunes")),
        "apple_bridge" to Info(Category.STREAMING, listOf("iTunes")),
        "ytm" to Info(Category.STREAMING, listOf("YTM", "YouTube")),
        "youtube" to Info(Category.STREAMING, listOf("YTM", "YouTube")),
        "amazon" to Info(Category.STREAMING, listOf("Prime Music")),
        "tidal" to Info(Category.STREAMING),
        "deezer" to Info(Category.STREAMING),
        "deezer_bridge" to Info(Category.STREAMING),
        "liveone_web" to Info(Category.STREAMING, listOf("LiveXLive", "Slacker")),
        "qobuz_web" to Info(Category.STREAMING),
        "soundcloud" to Info(Category.STREAMING),

        "subsonic" to Info(Category.SERVER, SUBSONIC_NAMES),
        "koel" to Info(Category.SERVER),
        "jellyfin" to Info(Category.SERVER, listOf("Finamp")),
        "emby" to Info(Category.SERVER),
        "plex" to Info(Category.SERVER, listOf("Plexamp")),
        "lyrion" to Info(Category.SERVER, listOf("Logitech Media Server", "Squeezebox", "Squeezelite")),
        "mopidy" to Info(Category.SERVER, listOf("Iris")),

        "charts" to Info(Category.RADIO, listOf("Top 100", "Billboard")),

        "lastfm" to Info(Category.LISTENING, listOf("Scrobbles", "Libre.fm")),
        "listenbrainz" to Info(Category.LISTENING, listOf("MusicBrainz", "Scrobbles")),

        "csv" to Info(Category.FILES, listOf("M3U", "CSV", "XSPF", "PLS", "iTunes Library")),

        "anghami" to Info(Category.REGIONAL, countries = MENA, languages = setOf("ar"), area = Area.SOUTH_ASIA_MIDDLE_EAST, tag = "MENA"),
        "anghami_web" to Info(Category.REGIONAL, countries = MENA, languages = setOf("ar"), area = Area.SOUTH_ASIA_MIDDLE_EAST, tag = "MENA"),
        "gaana" to Info(Category.REGIONAL, countries = setOf("IN"), languages = INDIA_LANGS, area = Area.SOUTH_ASIA_MIDDLE_EAST, tag = "IN"),
        "gaana_web" to Info(Category.REGIONAL, countries = setOf("IN"), languages = INDIA_LANGS, area = Area.SOUTH_ASIA_MIDDLE_EAST, tag = "IN"),
        "jiosaavn" to Info(Category.REGIONAL, listOf("Saavn"), countries = setOf("IN"), languages = INDIA_LANGS, area = Area.SOUTH_ASIA_MIDDLE_EAST, tag = "IN"),
        "netease" to Info(Category.REGIONAL, listOf("网易云音乐", "網易雲音樂", "NetEase", "Wangyi"), countries = CHINA, languages = setOf("zh"), area = Area.EAST_ASIA, tag = "CN"),
        "qqmusic" to Info(Category.REGIONAL, listOf("QQ音乐", "QQ音樂", "Tencent"), countries = CHINA, languages = setOf("zh"), area = Area.EAST_ASIA, tag = "CN"),
        "melon" to Info(Category.REGIONAL, listOf("멜론"), countries = setOf("KR"), languages = setOf("ko"), area = Area.EAST_ASIA, tag = "KR"),
    )

    private val STREAMING_INFO = Info(Category.STREAMING)

    /** What is known about [serviceId]; a service not listed is Streaming, with no other names. */
    fun info(serviceId: String): Info = INFO[serviceId] ?: STREAMING_INFO

    /** One row of the chooser: a service, with its ways in ([members], e.g. official API and web sign-in). */
    class Family(val name: String, val members: List<MusicProvider>) {
        val lead: MusicProvider get() = members.first()
        val category: Category get() = lead.category
        val area: Area? get() = members.firstNotNullOfOrNull { info(it.serviceId).area }
        val aliases: List<String> get() = members.flatMap { it.aliases }.distinct()
        val countries: Set<String> get() = members.flatMapTo(HashSet()) { info(it.serviceId).countries }
        val languages: Set<String> get() = members.flatMapTo(HashSet()) { info(it.serviceId).languages }
        val tag: String? get() = members.firstNotNullOfOrNull { info(it.serviceId).tag }
    }

    /** The services, one row per family, in the order given (the build's display order). */
    fun families(services: List<MusicProvider>): List<Family> =
        services.groupBy { it.familyName }.map { (name, members) -> Family(name, members) }

    /** The categories that have at least one row, in chip order (Games is hidden while empty). */
    fun categories(families: List<Family>): List<Category> {
        val used = families.mapTo(HashSet()) { it.category }
        return Category.entries.filter { it in used }
    }

    /** True where a regional service is at home for this phone (or browser): its country or its language. */
    fun isLocal(f: Family, country: String?, language: String?): Boolean {
        val c = country?.trim()?.uppercase().orEmpty()
        val l = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
        return (c.isNotEmpty() && c in f.countries) || (l.isNotEmpty() && l in f.languages)
    }

    /** Lower case, no accents, letters and digits only: "Airsonic-Advanced" and "airsonic advanced" are one. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    /** True when [query] is found in the family's name, its routes' names or its other names. */
    fun matches(f: Family, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return (listOf(f.name) + f.members.map { it.displayName } + f.aliases).any { normalize(it).contains(q) }
    }

    /** The rows matching [query], from every category: names that start with it first, then the rest, each in display order. */
    fun search(families: List<Family>, query: String): List<Family> {
        val q = normalize(query)
        if (q.isEmpty()) return families
        val hits = families.filter { matches(it, query) }
        val (first, rest) = hits.partition { normalize(it.name).startsWith(q) }
        return first + rest
    }

    /**
     * A block of the chooser: a category's rows (with [total], all of them, for "Show all"), the
     * regional ones suggested here ([suggested]), or one area's under the Regional chip.
     */
    class Section(val category: Category, val families: List<Family>, val total: Int, val area: Area? = null, val suggested: Boolean = false) {
        val more: Boolean get() = total > families.size
    }

    /**
     * The blocks shown for [chip] (null: All). All: each category with its first [preview] rows,
     * regional services only where they are at home ([isLocal]). A category: all of it. Regional:
     * the ones at home first, then the others by area.
     */
    fun sections(families: List<Family>, chip: Category?, country: String?, language: String?, preview: Int = PREVIEW): List<Section> {
        if (chip == null) return Category.entries.mapNotNull { cat ->
            val all = families.filter { it.category == cat }
            val shown = if (cat == Category.REGIONAL) all.filter { isLocal(it, country, language) } else all
            if (shown.isEmpty()) null else Section(cat, shown.take(preview), all.size)
        }
        val all = families.filter { it.category == chip }
        if (chip != Category.REGIONAL) return if (all.isEmpty()) emptyList() else listOf(Section(chip, all, all.size))
        val (here, elsewhere) = all.partition { isLocal(it, country, language) }
        val out = ArrayList<Section>()
        if (here.isNotEmpty()) out += Section(chip, here, here.size, suggested = true)
        Area.entries.forEach { a -> elsewhere.filter { it.area == a }.takeIf { it.isNotEmpty() }?.let { out += Section(chip, it, it.size, area = a) } }
        elsewhere.filter { it.area == null }.takeIf { it.isNotEmpty() }?.let { out += Section(chip, it, it.size) }
        return out
    }

    /** How many rows a category shows in All before "Show all". */
    const val PREVIEW = 4

    /** What the chooser is opened for: adding an account, or picking a sync's source or target. */
    enum class Role { ADD, SOURCE, TARGET }

    /** Why [p] cannot be picked for [role], as a string name, or null when it can. */
    fun reason(p: MusicProvider, role: Role): String? = when {
        role == Role.TARGET && !p.canWrite -> "chooser_cannot_target"
        else -> null
    }
}
