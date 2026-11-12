package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * `trackhop`, the command line: a client of the API (Api.kt, ApiPlaylists.kt) of a running Trackhop.
 * On the same computer, or inside the server's container (`docker exec trackhop trackhop …`), it
 * finds it through the key that Trackhop leaves in its data folder (LocalKey.kt), with nothing to
 * set up; any other server with --url and --token (or TRACKHOP_URL and TRACKHOP_TOKEN), the token
 * made on its page, Tools › API. Plain text for people (ASCII arrows and signs: Windows consoles
 * often lack the others), --json for scripts. Exit codes: 0 done,
 * 1 failed (a sync that ended with an error, a call refused), 2 wrong use, 3 no Trackhop reached.
 */
object Cli {
    class Failure(val code: Int, message: String) : Exception(message)

    /** One command, for `help`, `help <command>` and the shell completions: its words and what it does. */
    class Cmd(val name: String, val group: String, val usage: List<String>, val summary: String, val more: String = "", val subs: List<String> = emptyList())

    /** Every command: these, and the next release's (Features.v2, CliV2.kt). */
    val COMMANDS: List<Cmd> get() = BASE_COMMANDS + CliV2.commands

    private val BASE_COMMANDS = listOf(
        Cmd("syncs", "Syncs", listOf("syncs [--tag KEY=VALUE] [--label NAME] [--favorite]", "list [...]"), "list the syncs, with their last result",
            "Also as: trackhop list. --tag genre=metal lists the syncs with that tag (--tag genre: with that key, whatever its value; given twice, both are wanted),\n--label NAME those wearing that label (its name, its id or a colour), --favorite the starred ones."),
        Cmd("show", "Syncs", listOf("show <sync>"), "a sync's details and its last runs"),
        Cmd("run", "Syncs", listOf("run <sync>... | --all | --tag KEY=VALUE [--wait] [--anyway] [--confirm-removals]"), "start syncs (--wait: until they end, with the result)",
            "--tag genre=metal, --label NAME and --favorite start a group: the syncs with that tag, wearing that label, or starred (those turned on).\n--anyway runs a sync that stopped because its destination looked much smaller than it was: you say the smaller one is right.\n--confirm-removals (with syncs you name) makes the removals a run held back, this once: see them first with trackhop preview <sync>."),
        Cmd("new", "Syncs", listOf("new <service> <playlist> <service> [<playlist>] [options]"), "make a sync: from a playlist (or a link) to a playlist, a new one when left out",
            "Options: --name NAME, --schedule manual|hourly|every_6h|daily|weekly or --every MINUTES, --at HH:MM, --days mon,fri (or 1,5),\n--changes add|mirror|archive (mirror also removes what leaves the source; archive keeps every track ever seen), --follows, --color NAME|#RRGGBB,\n--label NAME (a label's name or id: the card takes its colour), --tag KEY=VALUE (or --tag KEY; as many as wanted), --favorite,\n--two-way (also makes the reverse sync, marked <->, between two existing playlists of yours), --two-way-removals (with it: a track removed on one side is removed on the other)."),
        Cmd("edit", "Syncs", listOf("edit <sync> [options]"), "change a sync: the same options as new", "Only what you name changes. --color none takes the colour off, --label none the label; --tag KEY=VALUE adds or changes a tag,\n--untag KEY takes one off; --favorite stars the sync, --unfavorite takes the star off.\n--two-way makes the reverse sync, --one-way deletes it (the playlists stay); --two-way-removals and --no-two-way-removals say whether a pair's removals travel too."),
        Cmd("enable", "Syncs", listOf("enable <sync> | disable <sync>"), "turn a sync's schedule on or off"),
        Cmd("disable", "Syncs", listOf("disable <sync>"), "turn a sync's schedule off"),
        Cmd("delete", "Syncs", listOf("delete <sync>"), "delete a sync (its playlists stay as they are)"),
        Cmd("move", "Syncs", listOf("move <sync> <position|up|down|top|bottom>"), "move a sync in the list (the order of Run all and of the widgets)"),
        Cmd("stop", "Syncs", listOf("stop <sync> | pause <sync> | resume <sync>"), "stop, pause or resume a running sync"),
        Cmd("pause", "Syncs", listOf("pause <sync>"), "pause a running sync"),
        Cmd("resume", "Syncs", listOf("resume <sync>"), "resume a paused sync"),
        Cmd("preview", "Syncs", listOf("preview <sync>"), "what a run would do now, changing nothing"),
        Cmd("crowded", "Syncs", listOf("crowded"), "whether many syncs reach one service at about the same time",
            "A service can take many syncs at once for unusual traffic and turn some away. trackhop spread gives them new times."),
        Cmd("spread", "Syncs", listOf("spread [--apply]"), "new times for the crowded syncs: shown only, saved with --apply",
            "The first keeps its time, each next one starts a little later; days, frequency and everything else stay."),
        Cmd("runs", "Runs and review", listOf("runs [<sync>] [--limit N] [--offset N]"), "the runs, newest first"),
        Cmd("report", "Runs and review", listOf("report <run>", "report [--since 24h] [--names] [--title T] [--description TEXT]"), "one run: what it added, removed, could not find, and its notes",
            "Without <run>: a GitHub issue made ready, with the diagnostics (as trackhop diagnostics) and a link that opens it\nprefilled. Nothing is sent: you read it and send it yourself. When the same error is already reported, it says\nwhere to comment instead, or that a newer version fixed it."),
        Cmd("review", "Runs and review", listOf(
            "review <sync> [--run ID]",
            "review <sync> ignore|keep|discard <n>...|all",
            "review <sync> pick <n> <track>",
            "review <sync> replace <n> <track>",
            "review <sync> restore",
        ), "the tracks a run could not find, those to check, those removed; and what to do with them",
            "Without --run, the newest run with something to review. <n> is a number from the list (or a track's id).\n" +
                "ignore: not found, not searched for again; keep: a match to check is right; discard: it is wrong, removed;\n" +
                "pick: the track to add for one not found; replace: the right one for a match to check; restore: put back what the run removed.\n" +
                "A <track> is \"Artist - Title\", a link from any service, or its id on the sync's destination.",
            listOf("ignore", "keep", "discard", "pick", "replace", "restore")),
        Cmd("services", "Services and playlists", listOf("services"), "the services and whether they are connected"),
        Cmd("playlists", "Services and playlists", listOf("playlists <service>"), "a service's playlists"),
        Cmd("tracks", "Services and playlists", listOf("tracks <service> <playlist>"), "a playlist's tracks"),
        Cmd("add", "Services and playlists", listOf("add <service> <playlist> <track>..."), "add tracks: \"Artist - Title\", a link from any service, or an id"),
        Cmd("remove", "Services and playlists", listOf("remove <service> <playlist> <track>..."), "remove tracks, named the same way"),
        Cmd("create", "Services and playlists", listOf("create <service> <name>"), "a new playlist"),
        Cmd("search", "Services and playlists", listOf("search <service> <words>... [--limit N]"), "look for a track on a service (\"Artist - Title\", or a link)"),
        Cmd("convert", "Services and playlists", listOf("convert <service> <track>..."), "the same tracks on another service: their ids and how sure the match is"),
        Cmd("duplicates", "Services and playlists", listOf("duplicates <service> <playlist> [--remove [--wait]]"), "the tracks a playlist has more than once (--remove: the extra copies go)"),
        Cmd("mbtags", "Services and playlists", listOf("mbtags [<service>]"), "the songs on your Navidrome, Jellyfin, Emby or Plex server without MusicBrainz tags",
            "With the tags a sync finds the exact recording. Nothing is changed. <service> can be left out when only one such server is connected."),
        Cmd("releases", "Services and playlists", listOf("releases <service> <playlist> [--days 7|30|90] [--upcoming]"), "the new albums, EPs and singles of a playlist's artists",
            "From Deezer and MusicBrainz, no account needed; each artist is asked once a day (a first search takes about a second per artist).\n" +
                "--days: released in the last 7, 30 (default) or 90 days; --upcoming: also what is announced."),
        Cmd("concerts", "Services and playlists", listOf("concerts <service> <playlist> [--country IT]"), "the upcoming concerts of a playlist's artists in a country (Ticketmaster, MusicBrainz)",
            "Asked only now, never on a schedule; answers are kept a day. --country is a two-letter code (the last one used on the page, or the\n" +
                "server's language's, without it). Ticketmaster needs a key: the server's own (Settings) or TICKETMASTER_API_KEY; without one, MusicBrainz's dates."),
        Cmd("setlists", "Services and playlists", listOf("setlists <service> <playlist>"), "the latest setlist.fm setlists of a playlist's artists, with their songs",
            "Asked only now, never on a schedule; answers are kept a week. Needs a setlist.fm key: the server's own (Settings) or SETLISTFM_API_KEY.\n" +
                "--json gives each setlist's tracks, ready for \"trackhop add\" or a File playlist (Tools › Setlists on the page saves one)."),
        Cmd("records", "Services and playlists", listOf("records <service> <playlist> [--currency EUR] [--kind all|records|merch]"), "the records (CD, vinyl, cassette), merch and official shops of a playlist's artists (Discogs, MusicBrainz, their shops)",
            "Asked only now; Discogs answers are kept six hours, MusicBrainz's links a week, the shops' merch a day. With the server's Discogs token (Settings)\n" +
                "the first records of each artist also say how many copies are for sale and from what price. --kind records leaves the merch out, --kind merch\n" +
                "skips Discogs. The artist's own Shopify shop and Bandcamp are read for both (their public product lists, robots.txt honoured): their records\n" +
                "(vinyl, CD, cassette) go with the records, the rest is the merch. Plain links, no affiliate tags."),
        Cmd("wanted", "Services and playlists", listOf("wanted [--format json|csv|m3u|text|lidarr] [--albums] [--all] [--sync S] [--service S] [--since 7d]"),
            "the tracks of your playlists missing on your own server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy), each once",
            "What your syncs to your own server could not find, without doubles. --format prints the list as a file (to save with > file);\n" +
                "lidarr is Lidarr's Custom List (artists with a known MusicBrainz id). --albums groups by album, --all shows the ticked ones too,\n" +
                "--sync and --service narrow it (a sync's name or id; a source or target service), --since 7d, 24h or 2026-11-01 only the newer ones."),
        Cmd("export", "Services and playlists", listOf("export <service> <playlist> [<file>] [--format csv|m3u|xspf|jspf|txt|opml]"), "a playlist as a file (to stdout without <file>)"),
        Cmd("files", "Files and backups", listOf(
            "files",
            "files get <name> [<file>] [--format F]",
            "files rm <name>...",
            "files zip <file.zip> [<name>...] [--format F]",
        ), "the playlist files (File service): list, download, delete, several in a .zip", subs = listOf("get", "rm", "zip")),
        Cmd("import", "Files and backups", listOf("import <file>"), "a playlist file into the File service (CSV, M3U, PLS, XSPF, JSPF, JSON, text)"),
        Cmd("patterns", "Files and backups", listOf(
            "patterns",
            "patterns layout auto|artist_title|artist_album_title|custom",
            "patterns set <pattern>...",
            "patterns clear",
            "patterns try <line> [--pattern P]...",
        ), "how track names in imported files are read, and your own patterns",
            "A pattern such as {artist}/{album}/{track} {title}, or a regular expression after re: (see the guide: Playlist files).",
            listOf("layout", "set", "clear", "try")),
        Cmd("backup", "Files and backups", listOf("backup [--wait]"), "back up every playlist now"),
        Cmd("config", "Files and backups", listOf("config [<file>]", "config import <file> [--dry-run]"), "the whole set-up as one file (to stdout without <file>), or brought back in",
            "import replaces the syncs with the same id and adds the others; --dry-run only says what the file holds. Sign-ins are not in the file.", listOf("import")),
        Cmd("notify", "Notifications", listOf(
            "notify",
            "notify kinds",
            "notify add <kind> [--name N] [--set key=value]... [--events changes,failed,review,removals]",
            "notify edit <channel> [--name N] [--set key=value]... [--events ...]",
            "notify on|off|rm|test <channel>",
        ), "the notification channels (their tokens masked): add, change, turn on or off, remove, test",
            "notify kinds lists the kinds and their settings. A secret left out of edit keeps its value.",
            listOf("kinds", "add", "edit", "on", "off", "rm", "test")),
        Cmd("webhook", "Notifications", listOf("webhook", "webhook set <url> [--secret S] [--events sync.started,sync.finished,sync.failed,backup.finished]", "webhook off"),
            "the address Trackhop POSTs its events to", subs = listOf("set", "off")),
        Cmd("settings", "Other", listOf("settings", "settings set <key> <value>"), "the settings: theme, backupSchedule, backupFolder, updatesCheck", subs = listOf("set")),
        Cmd("stats", "Other", listOf("stats"), "your numbers: tracks moved, time saved, busiest route, top artists"),
        Cmd("tasks", "Other", listOf("tasks", "task <id>"), "the background tasks (backups, duplicates), and one of them"),
        Cmd("task", "Other", listOf("task <id>"), "one background task"),
        Cmd("log", "Other", listOf("log [--lines N]"), "the server's last log lines (the admin's)"),
        Cmd("diagnostics", "Other", listOf("diagnostics [--since 24h] [--names] [--out <file>]"), "a report for asking for help: version, system, services, syncs, runs and log, without secrets",
            "--since: how far back (90m, 24h, 7d, 2w). Names of syncs, playlists and tracks are left out unless --names. The admin's."),
        Cmd("status", "Other", listOf("status"), "version, syncs, what is running"),
        Cmd("mcp", "Other", listOf("mcp"), "serve Trackhop's tools to an AI assistant (MCP, over stdin/stdout)"),
        Cmd("completion", "Other", listOf("completion bash|zsh|fish"), "the shell's completion for trackhop",
            "bash: trackhop completion bash > /etc/bash_completion.d/trackhop\nzsh: trackhop completion zsh > \"\${fpath[1]}/_trackhop\"\nfish: trackhop completion fish > ~/.config/fish/completions/trackhop.fish",
            listOf("bash", "zsh", "fish")),
        Cmd("help", "Other", listOf("help [<command>]"), "this text, or one command's"),
    )

    /** The switches with no value, and the options that take one (--name NAME or --name=NAME). */
    private val FLAGS = setOf("json", "wait", "all", "albums", "anyway", "dry-run", "remove", "follows", "favorite", "unfavorite", "confirm-removals", "two-way", "one-way", "two-way-removals", "no-two-way-removals", "apply", "names", "upcoming")
    private val VALUED = setOf("url", "token", "user", "name", "schedule", "every", "at", "days", "changes", "color", "format", "limit", "offset", "run", "secret", "events", "set", "lines", "pattern", "tag", "untag", "label", "sync", "service", "since", "out", "title", "description", "country", "currency") + CliV2.valued
    val OPTIONS: List<String> get() = (FLAGS + VALUED).sorted().map { "--$it" }

    /** The options a command was given: switches and values (a value given twice is a list: --set). */
    class Opts(val flags: Set<String> = emptySet(), val values: Map<String, List<String>> = emptyMap()) {
        fun has(flag: String) = flag in flags
        fun value(key: String) = values[key]?.lastOrNull()
        fun all(key: String) = values[key].orEmpty()
    }

    private val USAGE: String get() = buildString {
        append("Usage: trackhop [--json] [--user NAME] [--url URL --token TOKEN] <command> [arguments]\n")
        val shown = COMMANDS.filter { c -> c.name !in setOf("disable", "pause", "resume", "task") }
        for ((group, cmds) in shown.groupBy { it.group }) {
            append("\n").append(group).append('\n')
            for (c in cmds) {
                val first = c.usage.first()
                append("  ").append(first.padEnd(42)).append(' ').append(c.summary).append('\n')
            }
        }
        append(
            """
            |
            |A <sync> is its name (or the start of it) or its id; a <service> its name or id; a <playlist>
            |its name or id. Without --url, trackhop talks to the Trackhop running on this computer; on a
            |server shared by several people, --user NAME (or TRACKHOP_USER) picks whose, the admin's without.
            |--json prints what the API answers, for scripts. trackhop help <command> tells more of one.
            """.trimMargin(),
        )
    }

    private fun helpFor(name: String): String? {
        val c = COMMANDS.firstOrNull { it.name == name } ?: return null
        return buildString {
            c.usage.forEach { append("trackhop ").append(it).append('\n') }
            append('\n').append(c.summary.replaceFirstChar { it.uppercase() }).append(".\n")
            if (c.more.isNotEmpty()) append('\n').append(c.more).append('\n')
        }.trimEnd()
    }

    /** `trackhop completion <shell>`: the commands, their sub-commands and the options, for the shell to offer. */
    fun completion(shell: String): String? {
        val names = COMMANDS.map { it.name }.joinToString(" ")
        val subs = COMMANDS.filter { it.subs.isNotEmpty() }
        val opts = OPTIONS.joinToString(" ")
        return when (shell) {
            "bash" -> buildString {
                append("# trackhop completion for bash\n_trackhop() {\n  local cur=\"\${COMP_WORDS[COMP_CWORD]}\" cmd=\"\"\n")
                append("  for w in \"\${COMP_WORDS[@]:1:COMP_CWORD-1}\"; do case \"\${w}\" in -*) ;; *) cmd=\"\${w}\"; break;; esac; done\n")
                append("  if [[ \"\${cur}\" == -* ]]; then COMPREPLY=( \$(compgen -W \"$opts\" -- \"\${cur}\") ); return; fi\n")
                append("  case \"\${cmd}\" in\n")
                subs.forEach { append("    ${it.name}) COMPREPLY=( \$(compgen -W \"${it.subs.joinToString(" ")}\" -- \"\${cur}\") ); return;;\n") }
                append("    \"\") COMPREPLY=( \$(compgen -W \"$names\" -- \"\${cur}\") ); return;;\n  esac\n  COMPREPLY=( \$(compgen -f -- \"\${cur}\") )\n}\ncomplete -F _trackhop trackhop\n")
            }
            "zsh" -> buildString {
                append("#compdef trackhop\n# trackhop completion for zsh\n_trackhop() {\n  local -a cmds opts\n")
                append("  cmds=(${COMMANDS.joinToString(" ") { "'${it.name}:${it.summary.replace("'", "").replace(":", " -")}'" }})\n")
                append("  opts=($opts)\n")
                append("  if [[ \$words[CURRENT] == -* ]]; then compadd -- \$opts; return; fi\n")
                append("  if (( CURRENT == 2 )); then _describe 'command' cmds; return; fi\n  case \$words[2] in\n")
                subs.forEach { append("    ${it.name}) (( CURRENT == 3 )) && compadd -- ${it.subs.joinToString(" ")} && return;;\n") }
                append("  esac\n  _files\n}\ncompdef _trackhop trackhop\n")
            }
            "fish" -> buildString {
                append("# trackhop completion for fish\ncomplete -c trackhop -f\n")
                COMMANDS.forEach { append("complete -c trackhop -n '__fish_use_subcommand' -a ${it.name} -d '${it.summary.replace("'", "")}'\n") }
                subs.forEach { c -> append("complete -c trackhop -n '__fish_seen_subcommand_from ${c.name}' -a '${c.subs.joinToString(" ")}'\n") }
                (FLAGS + VALUED).sorted().forEach { o -> append("complete -c trackhop -l $o${if (o in VALUED) " -r" else ""}\n") }
            }
            else -> null
        }
    }


    /** Runs [args]; what it prints goes to [out] and [err]. Returns the exit code. */
    fun run(args: List<String>, env: Map<String, String>, out: PrintStream, err: PrintStream, dataDir: () -> File = { defaultDataDir(env) }): Int {
        val rest = ArrayList<String>()
        val flags = HashSet<String>()
        val values = LinkedHashMap<String, MutableList<String>>()
        var help = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> help = true
                a == "-V" || a == "--version" -> { out.println("trackhop ${cliVersion()}"); return 0 }
                // After "--" everything is words, even what starts with dashes (a track's name).
                a == "--" -> { rest += args.drop(i + 1); break }
                a.startsWith("--") -> {
                    val key = a.removePrefix("--").substringBefore('=')
                    val inline = a.substringAfter('=', "").takeIf { '=' in a }
                    when (key) {
                        in FLAGS -> if (inline == null) flags += key else return usage(err, "--$key takes no value")
                        in VALUED -> values.getOrPut(key) { ArrayList() } += inline ?: args.getOrNull(++i)
                            ?: return usage(err, "--$key needs ${when (key) { "url" -> "an address"; "token" -> "the API token"; "user" -> "a user name"; else -> "a value" }}")
                        else -> return usage(err, "unknown option $a")
                    }
                }
                else -> rest += a
            }
            i++
        }
        val opts = Opts(flags, values)
        val url = opts.value("url") ?: env["TRACKHOP_URL"]?.takeIf { it.isNotBlank() }
        val token = opts.value("token") ?: env["TRACKHOP_TOKEN"]?.takeIf { it.isNotBlank() }
        val user = opts.value("user") ?: env["TRACKHOP_USER"]?.takeIf { it.isNotBlank() }
        val command = (if (help) "help" else rest.firstOrNull()) ?: "help"
        val params = if (help) rest.take(1) else rest.drop(1)
        if (command == "help") {
            val about = params.firstOrNull()
            if (about == null) { out.println(USAGE); return 0 }
            out.println(helpFor(about) ?: return usage(err, "no command \"$about\""))
            return 0
        }
        if (command == "completion") {
            out.print(completion(params.firstOrNull().orEmpty()) ?: return usage(err, "usage: trackhop completion bash|zsh|fish"))
            return 0
        }
        return try {
            val adminName = System.getenv("SONGPORT_USER")?.trim()?.takeIf { it.isNotEmpty() }?.let { MultiUser.normalize(it) }
            val name = user?.let { MultiUser.normalize(it) }?.takeIf { it != MultiUser.ADMIN && it != adminName }
            if (name != null && !MultiUser.NAME.matches(name)) return usage(err, "no user can be called \"$user\"")
            // A person's Trackhop on a shared server keeps its key in their own folder (MultiUser.kt).
            val dir: () -> File = if (name == null) dataDir else { { File(File(dataDir(), MultiUser.USERS_DIR), name) } }
            // An MCP server for an AI assistant: it reaches Trackhop at the first tool call, so the
            // assistant can start it before Trackhop; stdout carries only the protocol.
            if (command == "mcp") {
                Mcp.serveStdio(System.`in`.bufferedReader(Charsets.UTF_8), out, cliVersion()) { connect(url, token, dir) }
                return 0
            }
            val api = connect(url, token, dir)
            CliCommands(api, out, opts.has("json")).run(command, params, opts)
        } catch (f: Failure) {
            err.println("trackhop: ${f.message}")
            f.code
        }
    }

    private fun usage(err: PrintStream, problem: String): Int {
        err.println("trackhop: $problem\nSee: trackhop help")
        return 2
    }

    private fun cliVersion(): String = Cli::class.java.getResourceAsStream("/songport-version.txt")?.use { String(it.readBytes()).trim() } ?: "dev"

    /** Where Trackhop keeps its data: SONGPORT_DATA (the container sets it), else Desktop's folder. */
    fun defaultDataDir(env: Map<String, String>): File =
        env["SONGPORT_DATA"]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: DesktopApp.dataDir(env)

    internal fun connect(url: String?, token: String?, dataDir: () -> File): Client {
        if (url != null) {
            if (token == null) throw Failure(2, "--url needs --token too (the API's token, made on the page: Tools › API)")
            return Client(url.trimEnd('/'), token)
        }
        val dir = dataDir()
        val (local, key) = LocalKey.read(dir)
            ?: throw Failure(3, "no Trackhop is running on this computer (nothing in ${dir.path}). Start Trackhop, or use --url and --token for a server elsewhere.")
        return Client(local, key)
    }

    /** The API at [base], with [token]. */
    class Client(private val base: String, private val token: String) {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun get(path: String): JsonElement = call("GET", path, null)
        fun post(path: String, body: JsonElement? = null): JsonElement = call("POST", path, body?.toString())
        fun send(method: String, path: String, body: JsonElement? = null): JsonElement = call(method, path, body?.toString())
        fun raw(method: String, path: String, body: ByteArray?): String =
            String(exchange(method, path, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) }, null), Charsets.UTF_8)
        /** A file the API answers with (a .zip): its bytes as they come. */
        fun bytes(method: String, path: String, body: JsonElement? = null): ByteArray =
            exchange(method, path, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }, if (body != null) "application/json" else null)

        private fun call(method: String, path: String, body: String?): JsonElement {
            val text = String(exchange(method, path, body?.let { HttpRequest.BodyPublishers.ofString(it) }, if (body != null) "application/json" else null), Charsets.UTF_8)
            return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        }

        private fun exchange(method: String, path: String, body: HttpRequest.BodyPublisher?, type: String?): ByteArray {
            val req = HttpRequest.newBuilder(URI("$base/api/v1/$path")).timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer $token")
                .apply { if (type != null) header("Content-Type", type) }
                .method(method, body ?: HttpRequest.BodyPublishers.noBody()).build()
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: ConnectException) {
                throw Failure(3, "Trackhop does not answer at $base: is it running?")
            } catch (e: java.io.IOException) {
                throw Failure(3, "Trackhop at $base: ${e.message ?: e.javaClass.simpleName}")
            }
            if (res.statusCode() in 200..299) return res.body()
            val message = runCatching { Json.parseToJsonElement(String(res.body(), Charsets.UTF_8)).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw Failure(if (res.statusCode() == 401 || res.statusCode() == 403) 3 else 1, message ?: "HTTP ${res.statusCode()}")
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.getenv(), System.out, System.err))
}
