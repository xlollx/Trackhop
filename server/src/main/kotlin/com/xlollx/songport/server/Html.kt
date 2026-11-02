package com.xlollx.songport.server

import java.text.SimpleDateFormat
import java.util.Date

/** Small pieces of HTML for the server's pages: no template engine, no script beyond what a page needs. */
object Html {
    fun h(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun date(epoch: Long): String = if (epoch <= 0) "never" else SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(epoch))

    /** A button that posts to [action]: every change goes through POST, never a link. */
    fun postButton(action: String, label: String, style: String = "", fields: Map<String, String> = emptyMap(), confirm: String? = null): String =
        """<form method="post" action="${h(action)}" class="inline"${confirm?.let { " onsubmit=\"return confirm('${h(it)}')\"" } ?: ""}>""" +
            fields.entries.joinToString("") { (k, v) -> """<input type="hidden" name="${h(k)}" value="${h(v)}">""" } +
            """<button class="${h(style)}">${h(label)}</button></form>"""

    /** Where the engine runs: in a container ([SERVER]) or as an app on the user's computer ([DESKTOP]). */
    /** [stage]: the server on Docker is a beta since 0.4.0, Trackhop Desktop since 0.4.1. */
    enum class Edition(val product: String, val guide: String, val stage: String) {
        SERVER("Trackhop server", "https://trackhop.org/guide/docker", "beta"),
        DESKTOP("Trackhop Desktop", "https://trackhop.org/guide/desktop", "beta"),
    }

    /** The Trackhop mark (the app's ic_brand_mark), white, for the header's tile. */
    private const val MARK = """<svg viewBox="0 0 66 66" aria-hidden="true"><g transform="translate(-21 -21)" fill="#fff" stroke="#fff" stroke-linecap="round"><path d="M30.6,40.5 A27,27 0 0 1 77.4,40.5" fill="none" stroke-width="5.5"/><path d="M77.4,67.5 A27,27 0 0 1 30.6,67.5" fill="none" stroke-width="5.5"/><path d="M81.85,36.2 L71.45,42.2 L81.4,47.4 Z" stroke="none"/><path d="M26.15,71.8 L36.55,65.8 L26.6,60.6 Z" stroke="none"/><g transform="translate(2.1 1.5)"><path d="M54.08,61.11 A7,5.6 -20 1 1 40.92,65.89 A7,5.6 -20 1 1 54.08,61.11 Z" stroke="none"/><path d="M52.6,62.5 L52.6,37.5" fill="none" stroke-width="3.6"/><path d="M52.6,37.2 C58.5,38.6 63.4,42.4 63.2,49.6 C61.6,45.2 58,42.9 52.6,42.6 Z" stroke="none"/></g></g></svg>"""

    /**
     * In Desktop's own window, a link to another site opens in the system's browser: the window only
     * shows Trackhop (and a service's sign-in, which a form starts, not a link).
     */
    private const val DESKTOP_LINKS = """<script>document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a||a.host===location.host)return;e.preventDefault();location.href='trackhop-external:'+a.href})</script>"""

    private val NAV = listOf("/" to "Syncs", "/playlists" to "Playlists", "/connectors" to "Services", "/tools" to "Tools", "/log" to "Log", "/settings" to "Settings")

    /**
     * A service's mark as the app draws it: its glyph (ServiceMarks) white on a circle in its brand
     * colour; a folder or a server icon for File and Subsonic; the initial otherwise.
     */
    fun logo(serviceId: String?, brandColor: Long?, name: String, size: Int = 32): String {
        val color = brandColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: "#6E6878"
        val glyph = com.xlollx.songport.providers.ServiceMarks.pathData(serviceId)?.let { """<svg viewBox="0 0 24 24"><path fill="#fff" d="$it"/></svg>""" }
            ?: when (serviceId) {
                "csv" -> """<svg viewBox="0 0 24 24"><path fill="#fff" d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>"""
                "subsonic" -> """<svg viewBox="0 0 24 24"><path fill="#fff" d="M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"/></svg>"""
                else -> """<b>${h(name.take(1).uppercase())}</b>"""
            }
        return """<span class="logo" style="--s:${size}px;background:$color" role="img" aria-label="${h(name)}">$glyph</span>"""
    }

    /** The colours a sync can wear, as the app's JobColor: key to light and dark shade. */
    val JOB_COLORS = linkedMapOf(
        "red" to ("#E5484D" to "#FF7A7A"), "orange" to ("#F76B15" to "#FF9A5C"), "yellow" to ("#D69E00" to "#FFCB47"),
        "green" to ("#2F9E62" to "#5BD18F"), "teal" to ("#12A594" to "#3DD6C3"), "blue" to ("#0B84E8" to "#63B3FF"),
        "purple" to ("#8E4EC6" to "#B98AF0"), "pink" to ("#D6409F" to "#F57CC6"),
    )

    /** The style attribute that gives a card a sync's colour, or "" for the default look. */
    fun jobStyle(key: String?): String = JOB_COLORS[key]?.let { (light, dark) -> """ style="--job:$light;--job-dark:$dark"""" } ?: ""

    /**
     * Where "Back" leads from [path] (no leading slash): the level above, by the page's own address
     * (not the browser's history, which holds the forms' redirects); null on the tabs themselves.
     */
    fun backTo(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1 && parts.firstOrNull() != "syncs") return null
        return when {
            parts[0] == "syncs" && parts.size <= 2 -> "/"
            parts[0] == "syncs" && parts.size == 4 && parts[2] == "review" -> "/syncs/" + java.net.URLEncoder.encode(parts[1], Charsets.UTF_8)
            parts[0] == "tools" && parts.getOrNull(1) == "task" -> "/tools"
            parts[0] == "files" -> "/connectors/csv"
            else -> "/" + parts.dropLast(1).joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        }
    }

    /** Who is signed in, on a server shared by several people (MultiUser.kt); null on a single-user one. */
    class Account(val name: String, val admin: Boolean)

    /** The header's links: the pages, then (several people) the users' page for the admin and one's own account. */
    private fun nav(path: String, account: Account?): String {
        val links = NAV + listOfNotNull(
            ("/users" to "Users").takeIf { account?.admin == true },
            account?.let { "/account" to "👤 ${h(it.name)}" },
        )
        return links.joinToString("") { (href, label) ->
            val on = if (href == "/") path.isEmpty() || path.startsWith("syncs") else path.startsWith(href.drop(1))
            """<a href="$href"${if (on) " class=\"on\" aria-current=\"page\"" else ""}>$label</a>"""
        }
    }

    fun page(
        title: String, body: String, version: String, refreshSeconds: Int? = null, edition: Edition = Edition.SERVER, path: String = "",
        account: Account? = null,
        /** False for the sign-in page: nothing to go to before signing in. */
        showNav: Boolean = true,
        /** "light" or "dark" to set the look; anything else follows the system. */
        theme: String = "system",
        /** A line under the header (a new version is out), or "". */
        banner: String = "",
        /** The home page's two columns: a wider page on a wide screen. */
        wide: Boolean = false,
    ): String = """<!doctype html>
<html lang="en"${if (theme == "light" || theme == "dark") " data-theme=\"$theme\"" else ""}><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#141218">
${refreshSeconds?.let { """<meta http-equiv="refresh" content="$it">""" } ?: ""}
<title>${h(title)} · Trackhop</title>
<link rel="stylesheet" href="/style.css">
${if (edition == Edition.DESKTOP) DESKTOP_LINKS else Pwa.HEAD}
</head><body>
<header class="top"><a class="brand" href="/"><span class="tile">$MARK</span>Trackhop <span class="${edition.stage}">${edition.stage}</span></a>
${if (showNav) "<nav>${nav(path, account)}</nav>" else ""}</header>
$banner<p class="alpha-note">${if (edition.stage == "beta") "<strong>Beta.</strong> ${h(edition.product)} works for everyday use but is still young: things can change between releases." else "<strong>Alpha.</strong> ${h(edition.product)} is an early test version: things can break or change between releases."} Keep a copy of the data folder and <a href="https://trackhop.org/guide/reporting-a-problem">report what goes wrong</a>.</p>
<main${if (wide) " class=\"wide\"" else ""}>
${backTo(path)?.let { """<a class="back" href="${h(it)}">‹ Back</a>""" } ?: ""}
<h1>${h(title)}</h1>
$body
</main>
<footer><a class="coffee" href="https://ko-fi.com/xlollx" target="_blank" rel="noopener">☕ Buy me a coffee</a> <span class="muted">Optional: nothing is unlocked, everything stays free.</span><br>
${h(edition.product)} ${h(version)} · ${edition.stage} · <a href="${edition.guide}">Guide</a> · <a href="https://github.com/xlollx/Trackhop">GitHub</a> · <a href="https://github.com/sponsors/xlollx" target="_blank" rel="noopener">Sponsor</a>${if (edition == Edition.DESKTOP) " · " + postButton("/quit", "Quit Trackhop", "link", confirm = "Stop Trackhop? Scheduled syncs run only while it is running.") else ""}</footer>
</body></html>"""

    /**
     * The app's look (ui/Theme.kt): its Material 3 colours, dark unless the system is light; large
     * soft cards, pill buttons, and the brand "beam" (the same stops as Brand.beam and the site's
     * --beam) behind the header and the home page's hero.
     */
    const val CSS = """
:root{--bg:#fbf8ff;--surface:#f6f2fc;--surface-2:#eae6f1;--line:#cbc4d1;--ink:#1c1b22;--ink-2:#49454f;--ink-3:#6e6878;--accent:#5b3fd9;--accent-ink:#fff;--accent-2:#e6deff;--accent-2-ink:#1b0062;--link:#4c32c4;--ok:#1b7f4b;--err:#b3261e;--radius:20px;color-scheme:light}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#141218;--surface:#1d1b20;--surface-2:#2b2930;--line:#49454f;--ink:#e6e1ea;--ink-2:#cac4d0;--ink-3:#948f99;--accent:#c8bfff;--accent-ink:#2b0a9a;--accent-2:#4327c0;--accent-2-ink:#e6deff;--link:#c8bfff;--ok:#7dd8a3;--err:#ffb4ab;color-scheme:dark}}
:root[data-theme=dark]{--bg:#141218;--surface:#1d1b20;--surface-2:#2b2930;--line:#49454f;--ink:#e6e1ea;--ink-2:#cac4d0;--ink-3:#948f99;--accent:#c8bfff;--accent-ink:#2b0a9a;--accent-2:#4327c0;--accent-2-ink:#e6deff;--link:#c8bfff;--ok:#7dd8a3;--err:#ffb4ab;color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
a{color:var(--link)}
.top{display:flex;flex-wrap:wrap;gap:10px 20px;align-items:center;justify-content:space-between;padding:12px 16px;color:#fff;
 background:linear-gradient(12deg,#000 0%,#1a0000 45%,#400000 80%,#2a10a0 100%),#000;position:relative;overflow:hidden}
.top::after{content:"";position:absolute;inset:0;background:linear-gradient(90deg,#000,#660000);mix-blend-mode:screen;pointer-events:none}
.top>*{position:relative;z-index:1}
.brand{display:inline-flex;align-items:center;gap:10px;font-weight:700;font-size:19px;color:#fff;text-decoration:none}
.tile{display:inline-flex;width:36px;height:36px;padding:6px;border-radius:10px;background:rgba(255,255,255,.18);box-shadow:inset 0 0 0 1px rgba(255,255,255,.35)}
.tile svg{width:100%;height:100%}
.top nav{display:flex;gap:4px;flex-wrap:wrap}
.top nav a{color:#fff;text-decoration:none;padding:6px 14px;border-radius:999px;font-weight:600;font-size:14px}
.top nav a:hover{background:rgba(255,255,255,.14)}
.top nav a.on{background:rgba(255,255,255,.92);color:#1c1b22}
main{max-width:920px;margin:0 auto;padding:16px;overflow-wrap:anywhere}
h1{font-size:26px;margin:8px 0 16px;letter-spacing:-.01em}
.back{display:inline-block;margin:4px 0 0;padding:4px 12px 4px 8px;border-radius:999px;background:var(--surface-2);color:var(--ink);text-decoration:none;font-weight:600;font-size:14px}
.back:hover{background:var(--accent-2);color:var(--accent-2-ink)}
h2{font-size:18px;margin:24px 0 8px}
h3{margin:0 0 4px;font-size:16px}
.card{background:var(--surface);border-radius:var(--radius);padding:16px 18px;margin:0 0 12px}
.card[style*="--job"]{background:color-mix(in srgb,var(--job) 12%,var(--surface));box-shadow:inset 4px 0 0 var(--job)}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]) .card[style*="--job"]{background:color-mix(in srgb,var(--job-dark) 12%,var(--surface));box-shadow:inset 4px 0 0 var(--job-dark)}}
:root[data-theme=dark] .card[style*="--job"]{background:color-mix(in srgb,var(--job-dark) 12%,var(--surface));box-shadow:inset 4px 0 0 var(--job-dark)}
.hero{border-radius:28px;padding:20px 22px;margin:0 0 16px;color:#fff;position:relative;overflow:hidden;
 background:linear-gradient(12deg,#000 0%,#2a0000 50%,#400000 66%,#421aff 70%,#59ffff 80%,#fff 100%)}
.hero::after{content:"";position:absolute;inset:0;background:linear-gradient(90deg,#000,#990000);mix-blend-mode:screen;pointer-events:none}
.hero>*{position:relative;z-index:1}
.hero h2{margin:0 0 4px;font-size:22px}
.hero .muted{color:rgba(255,255,255,.85)}
.hero button{background:#fff;color:#1c1b22}
.hero-link{color:#fff;font-weight:600;font-size:14px;text-decoration:none;margin-left:6px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin:0 0 12px}
.stat{margin:0;display:flex;flex-direction:column;gap:2px}.stat b{font-size:24px}
.row{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between}
.who{display:flex;gap:12px;align-items:center;min-width:0}
.pair{display:inline-flex;align-items:center}
.pair .logo+.logo{margin-left:-8px;box-shadow:0 0 0 2px var(--surface)}
.logo{display:inline-flex;align-items:center;justify-content:center;flex:none;width:var(--s);height:var(--s);border-radius:50%;color:#fff;font-size:calc(var(--s)*.45)}
.logo svg{width:56%;height:56%}
.muted{color:var(--ink-3);font-size:13px}
.ok{color:var(--ok)}.err{color:var(--err)}
.card.ok,.card.err{color:var(--ink)}
.card.ok{box-shadow:inset 4px 0 0 var(--ok)}.card.err{box-shadow:inset 4px 0 0 var(--err)}
.alpha{display:inline-block;font-size:10.5px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;padding:1px 8px;border-radius:999px;background:#ffb4ab;color:#690005;vertical-align:middle}
.beta{display:inline-block;font-size:10.5px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;padding:1px 8px;border-radius:999px;background:#c8e6ff;color:#003258;vertical-align:middle}
.update-note{margin:0;padding:10px 16px;background:var(--accent-2);color:var(--accent-2-ink);font-size:14px;font-weight:600;text-align:center}
.update-note a{color:inherit}
.alpha-note{margin:0;padding:10px 16px;background:var(--surface-2);color:var(--ink-2);font-size:13.5px;text-align:center}
.badge{display:inline-block;font-size:12px;padding:2px 10px;border-radius:999px;background:var(--surface-2);color:var(--ink-2)}
button,.button{font:inherit;font-weight:600;font-size:14px;cursor:pointer;border:0;border-radius:999px;padding:9px 18px;background:var(--accent);color:var(--accent-ink);text-decoration:none;display:inline-block;line-height:1.3}
button:hover,.button:hover{filter:brightness(1.08)}
button.secondary,.button.secondary{background:var(--accent-2);color:var(--accent-2-ink)}
button.danger{background:transparent;color:var(--err);box-shadow:inset 0 0 0 1px var(--line)}
form.inline{display:inline}
button.link{background:none;padding:0;border-radius:0;color:var(--link);text-decoration:underline;font-weight:400;font-size:inherit}
label{display:block;margin:12px 0 6px;font-weight:600;font-size:14px}
input[type=text],input[type=password],input[type=url],input[type=file],select,textarea{width:100%;font:inherit;padding:10px 12px;border-radius:12px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
.check{display:flex;gap:8px;align-items:center;font-weight:400}
.check.inline{display:inline-flex;margin-right:10px}
svg.kind{vertical-align:-3px;color:var(--accent)}
pre{white-space:pre-wrap;word-break:break-word;font-size:12.5px;background:var(--surface);border-radius:var(--radius);padding:14px;overflow:auto}
code{background:var(--surface-2);padding:1px 6px;border-radius:6px;word-break:break-all}
ol li,ul li{margin:4px 0}
details summary{cursor:pointer}
footer{max-width:920px;margin:24px auto;padding:0 16px 24px;color:var(--ink-3);font-size:13px;line-height:2}
.coffee{display:inline-block;padding:4px 12px;border-radius:999px;background:var(--surface-2);color:var(--ink);text-decoration:none;font-weight:600;line-height:1.5}
.coffee:hover{background:var(--accent-2);color:var(--accent-2-ink)}
.actions{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
.swatches{display:flex;gap:8px;flex-wrap:wrap}
.swatches label{margin:0;display:inline-flex;cursor:pointer}
.swatches input{position:absolute;opacity:0;width:1px;height:1px}
.dot{display:inline-block;width:30px;height:30px;border-radius:50%;box-shadow:inset 0 0 0 1px rgba(0,0,0,.15)}
.dot.none{background:transparent;box-shadow:inset 0 0 0 2px var(--line)}
.swatches input:checked+.dot{outline:3px solid var(--ink);outline-offset:2px}
.swatches input:focus-visible+.dot{outline:3px dashed var(--link);outline-offset:2px}
a b,.card a{text-decoration:none}
progress{width:100%;accent-color:var(--accent)}
main.wide{max-width:1240px}
.dash{display:grid;gap:0 20px}
@media (min-width:1000px){.dash{grid-template-columns:320px minmax(0,1fr)}.dash-side{order:-1;position:sticky;top:12px;align-self:start}.dash.side-right{grid-template-columns:minmax(0,1fr) 320px}.dash.side-right .dash-side{order:1}.dash.no-side{grid-template-columns:minmax(0,1fr)}}
.dash-side h3{margin-bottom:8px}
.figures{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:0 0 12px}
.figures div{margin:0}.figures dt{color:var(--ink-3);font-size:13px}.figures dd{margin:0;font-size:22px;font-weight:700}
.runs,.services{list-style:none;margin:0;padding:0}
.runs li{margin:0 0 8px}
.services{display:flex;flex-direction:column;gap:6px;margin-bottom:10px}.services li{display:flex;align-items:center;gap:8px;margin:0}
svg.activity{width:100%;height:auto;display:block;margin-top:6px}
svg.activity .bar{fill:var(--accent)}
svg.activity .col:hover .bar{fill:var(--link);filter:brightness(1.15)}
svg.activity .axis{stroke:var(--line);stroke-width:1}
svg.activity .tick{fill:var(--ink-3);font-size:10px}
@media (max-width:560px){h1{font-size:22px}.top{padding:10px 12px}}
"""
}
