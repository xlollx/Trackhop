package com.xlollx.songport.server

import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.ServiceCatalog
import com.xlollx.songport.providers.ServiceCatalog.Category
import com.xlollx.songport.server.Html.h
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Which service?" on the server's page, as in the app (ServiceCatalog): a search box, the accounts
 * already added, the category chips, each category's first rows and "Show all". Plain links and a
 * GET form: the page filters on the server, no script needed.
 */
internal object ServiceChooser {
    /** The services the server offers to add (a link-only source is offered where a link is pasted). */
    fun families(env: com.xlollx.songport.platform.Env): List<ServiceCatalog.Family> =
        ServiceCatalog.families(Providers.services().filter { !it.linkOnly && !withheld(env, it) })

    /** Deezer accepts no new apps that use its API: its official route only with an app id here, or once added. */
    fun withheld(env: com.xlollx.songport.platform.Env, p: MusicProvider): Boolean = p is com.xlollx.songport.providers.DeezerProvider && !p.offered(env)

    /** True when this server keeps the official Deezer from new users. */
    fun deezerWithheld(env: com.xlollx.songport.platform.Env): Boolean = Providers.services().any { withheld(env, it) }

    /** Country and language from the browser's Accept-Language ("it-IT,it;q=0.9"), else the server's own language. */
    fun place(acceptLanguage: String?, fallback: java.util.Locale): Pair<String?, String?> {
        val first = acceptLanguage?.split(',')?.firstOrNull()?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        val l = first?.let { java.util.Locale.forLanguageTag(it) } ?: fallback
        return l.country.takeIf { it.isNotBlank() } to l.language.takeIf { it.isNotBlank() }
    }

    /** One more account of a service already added, or the first: what "Add" posts for [p]. */
    fun addValue(p: MusicProvider): String? {
        val added = Providers.connectors()
        return when {
            added.none { it.serviceId == p.serviceId } -> p.id
            Accounts.allowsAnother(p) -> p.serviceId + Accounts.ANOTHER
            else -> null
        }
    }

    /** The catalogue as JSON (GET /api/v1/catalog): each service with its category, other names and ways in. */
    fun json(env: ServerEnv, query: String?, category: Category?): JsonArray {
        val all = families(env)
        val list = if (!query.isNullOrBlank()) ServiceCatalog.search(all, query) else all.filter { category == null || it.category == category }
        return JsonArray(list.map { f ->
            buildJsonObject {
                put("name", f.name)
                put("category", f.category.key)
                put("categoryName", env.text(f.category.stringName))
                put("area", f.area?.let { JsonPrimitive(env.text(it.stringName)) } ?: JsonNull)
                put("aliases", JsonArray(f.aliases.map { JsonPrimitive(it) }))
                put("routes", JsonArray(f.members.map { m ->
                    buildJsonObject {
                        put("service", m.serviceId); put("name", m.displayName)
                        put("route", m.route?.name?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("canWrite", m.canWrite)
                        put("unofficial", m is CookieConnector)
                        put("add", addValue(m)?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                }))
            }
        })
    }
}

/** GET /connectors/add: the chooser (?q= search, ?cat= a category), or one service's ways in (?service=). */
internal fun Web.serviceChooser(r: Web.Req) {
    val families = ServiceChooser.families(env)
    r.query["service"]?.let { id ->
        val f = families.firstOrNull { f -> f.members.any { it.id == id } } ?: return send(r.ex, 404, errorPage(404))
        return send(r.ex, 200, page(f.name, routesPage(f)))
    }
    val query = r.query["q"].orEmpty().trim()
    val chip = Category.of(r.query["cat"])
    val (country, language) = ServiceChooser.place(r.ex.requestHeaders.getFirst("Accept-Language"), env.locale)
    val body = StringBuilder(notice(r))
    val chipParam = chip?.let { """<input type="hidden" name="cat" value="${h(it.key)}">""" }.orEmpty()
    val placeholder = env.plural("chooser_search", families.size, families.size)
    body.append(
        """<form method="get" action="/connectors/add" class="finder" role="search">$chipParam<input type="search" name="q" value="${h(query)}" placeholder="${h(placeholder)}" aria-label="${h(placeholder)}"> <button class="secondary">Find</button>${if (query.isNotEmpty()) """ <a href="/connectors/add${chip?.let { "?cat=" + q(it.key) }.orEmpty()}">${h(env.text("chooser_clear_search"))}</a>""" else ""}</form>""",
    )
    if (query.isNotEmpty()) {
        // Typing: results from every category, each saying where it belongs.
        val hits = ServiceCatalog.search(families, query)
        if (hits.isEmpty()) body.append("""<p class="muted">${h(env.text("chooser_no_results", query))}</p>""")
        else body.append("""<div class="card">""").append(hits.joinToString("") { row(it, searching = true) }).append("</div>")
        return send(r.ex, 200, page(env.text("connector_pick_title"), body.toString()))
    }
    // The accounts already here, with their colour: each opens its page.
    val accounts = Providers.connectors()
    if (chip == null && accounts.isNotEmpty()) {
        body.append(sectionHead(env.text("chooser_your_accounts"), env.text("connected")))
        body.append("""<p class="chips">""")
        accounts.forEach { p -> body.append("""<a class="chip" href="/connectors/${q(p.id)}">${logo(p.id, 20)} ${h(p.label(env))}</a>""") }
        body.append("</p>")
    }
    // The chips: All, then each category with something in it (Games waits for its first member).
    body.append("""<p class="chips">""")
    body.append(chipLink(env.text("chooser_all"), "/connectors/add", chip == null))
    ServiceCatalog.categories(families).forEach { c -> body.append(chipLink(env.text(c.stringName), "/connectors/add?cat=${q(c.key)}", chip == c)) }
    body.append("</p>")
    ServiceCatalog.sections(families, chip, country, language).forEach { s ->
        val title = when {
            s.suggested -> env.text("chooser_suggested_here")
            s.area != null -> env.text(s.area!!.stringName)
            else -> env.text(s.category.stringName)
        }
        val side = when {
            s.suggested -> country?.let { java.util.Locale("", it).getDisplayCountry(env.locale) }.orEmpty()
            s.area != null -> ""
            else -> s.total.toString()
        }
        body.append(sectionHead(title, side))
        body.append("""<div class="card">""").append(s.families.joinToString("") { row(it, searching = false) })
        if (chip == null && s.more) body.append("""<p><a href="/connectors/add?cat=${q(s.category.key)}">${h(env.text("chooser_show_all", s.total))} ›</a></p>""")
        body.append("</div>")
    }
    // No Deezer at all (the Microsoft Store build): why, in a line.
    if (chip == null && families.none { it.name == "Deezer" } && ServiceChooser.deezerWithheld(env)) body.append(deezerWhy(official = false))
    send(r.ex, 200, page(env.text("connector_pick_title"), body.toString()))
}

/** Why Deezer, or its official route, is not offered: Deezer accepts no new apps (as the app says it). */
private fun Web.deezerWhy(official: Boolean): String =
    """<div class="card"><b>${h(env.text(if (official) "deezer_why_official_title" else "deezer_why_title"))}</b><p class="muted">${h(env.text("deezer_why_body"))}</p></div>"""

private fun Web.chipLink(label: String, href: String, on: Boolean) =
    """<a class="chip${if (on) " on" else ""}" href="$href"${if (on) " aria-current=\"page\"" else ""}>${h(label)}</a>"""

private fun sectionHead(title: String, side: String) =
    """<h2 class="row"><span>${h(title)}</span>${if (side.isNotEmpty()) """ <span class="muted">${h(side)}</span>""" else ""}</h2>"""

/** One service: its mark, its name linking to its ways in, and one line (where it belongs, other names, or the routes). */
private fun Web.row(f: ServiceCatalog.Family, searching: Boolean): String {
    val allTaken = f.members.all { ServiceChooser.addValue(it) == null }
    val line = when {
        allTaken -> env.text("connected")
        searching -> listOfNotNull(env.text(f.category.stringName), f.area?.let { env.text(it.stringName) }).joinToString(" · ")
        f.aliases.isNotEmpty() && f.category == Category.SERVER -> f.aliases.take(3).joinToString(", ") + if (f.aliases.size > 3) "…" else ""
        else -> f.members.sortedBy { if (it.route == MusicProvider.Route.EASY) 0 else 1 }.mapNotNull { m ->
            m.route?.let { env.text(if (it == MusicProvider.Route.EASY) "route_easy" else "route_official") }
        }.joinToString(" · ").ifEmpty { if (!f.lead.canWrite) env.text("read_only") else "" }
    }
    val unofficial = if (f.members.all { it is CookieConnector }) """ <span class="badge">unofficial</span>""" else ""
    val tag = f.tag?.let { """ <span class="badge">${h(it)}</span>""" }.orEmpty()
    val name = h(f.name)
    val href = "/connectors/add?service=${q(f.lead.id)}"
    return """<div class="who" style="padding:6px 0${if (allTaken) ";opacity:.55" else ""}">${Html.logo(f.lead.serviceId, f.lead.brandColor, f.name, 32)}<div><a href="$href"><b>$name</b></a>$unofficial$tag""" +
        (if (line.isNotEmpty()) """<br><span class="muted">${h(line)}</span>""" else "") + "</div></div>"
}

/** A service's ways in: each with what it is, a name for the account, and Add (or its page when already added). */
private fun Web.routesPage(f: ServiceCatalog.Family): String {
    val sb = StringBuilder("""<p><a href="/connectors/add">‹ ${h(env.text("connector_pick_title"))}</a></p>""")
    if (f.members.size > 1) sb.append("""<p class="muted">${h(env.text("connector_pick_hint"))}</p>""")
    f.members.sortedBy { if (it.route == MusicProvider.Route.EASY) 0 else 1 }.forEach { m ->
        val title = m.route?.let { env.text(if (it == MusicProvider.Route.EASY) "route_easy" else "route_official") } ?: m.displayName
        val badges = (if (m is CookieConnector) """ <span class="badge">unofficial</span>""" else "") +
            (if (m.alpha) """ <span class="alpha">alpha</span>""" else if (m.beta) """ <span class="badge">beta</span>""" else "") +
            (if (!m.canWrite) """ <span class="badge">${h(env.text("read_only"))}</span>""" else "")
        sb.append("""<div class="card"><div class="who">${Html.logo(m.serviceId, m.brandColor, m.displayName, 40)}<div><b>${h(title)}</b>$badges""")
        m.routeNoteName?.let { sb.append("""<br><span class="muted">${h(env.text(it))}</span>""") }
        sb.append("</div></div>")
        // Spotify (web) cannot get its token here for now: said before it is added.
        if (m.serviceId == SpotifyWebServerProvider.SERVICE) sb.append("""<p class="err">${h(env.text(SpotifyWebServerProvider.UNAVAILABLE))}</p>""")
        val value = ServiceChooser.addValue(m)
        if (value == null) {
            val added = Providers.connectors().first { it.serviceId == m.serviceId }
            sb.append("""<p><span class="ok">${h(env.text("connected"))}</span> · <a href="/connectors/${q(added.id)}">${h(added.label(env))}</a></p>""")
        } else {
            sb.append("""<form method="post" action="/connectors/add"><input type="hidden" name="service" value="${h(value)}">""")
            sb.append("""<label>${h(env.text("connector_name_label"))} (optional)</label><input type="text" name="name" maxlength="80" autocomplete="off" placeholder="${h(m.displayName)}">""")
            sb.append("""<p><button>Add</button></p></form>""")
        }
        sb.append("</div>")
    }
    // Deezer with its web route only: why the official one is missing, where its card was.
    if (f.name == "Deezer" && f.members.none { it is com.xlollx.songport.providers.DeezerProvider } && ServiceChooser.deezerWithheld(env)) sb.append(deezerWhy(official = true))
    return sb.toString()
}

/** The catalogue for the API's callers: [ServiceChooser.json]. */
internal fun catalogJson(env: ServerEnv, query: String?, category: String?): JsonArray = ServiceChooser.json(env, query, Category.of(category))
