---
title: Sync playlists from the command line (the trackhop command)
description: Make, run and review playlist syncs, edit playlists and files, and set up notifications from a terminal, a script or a scheduled task, with the trackhop command of Trackhop Desktop and the Docker server. Free and open source.
---

# The `trackhop` command

Trackhop Desktop and the Docker server come with a command line, `trackhop`: start syncs, see how
they went, list playlists, add and remove tracks, from a terminal, a script, a cron job or Windows'
Task Scheduler. It talks to the Trackhop that is running: on the same computer, or inside the same
container, it needs nothing set up.

```
trackhop syncs                     # every sync, with its last result
trackhop run "Road trip" --wait    # run one and wait for the result
trackhop run --all                 # run every sync that is turned on
trackhop add spotify "Focus" "Bonobo - Kerala" https://music.youtube.com/watch?v=…
```

## Where it is

| Where Trackhop runs | The command |
|---|---|
| Docker | `docker exec trackhop trackhop …` (the container's name, then the command) |
| Windows, installed with Scoop | `trackhop …` |
| Windows, installer (.msi) | `"%LOCALAPPDATA%\Trackhop\trackhop-cli.exe" …` |
| Windows, portable (.zip) | `trackhop-cli.exe` in the `Trackhop` folder |
| macOS | `/Applications/Trackhop.app/Contents/MacOS/trackhop-cli …` |
| Linux, .deb | `/opt/trackhop/bin/trackhop-cli …` |
| Linux, .tar.gz | `Trackhop/bin/trackhop-cli` where you unpacked it |

On Windows the program itself is `Trackhop.exe`, so the command is `trackhop-cli`. To type just
`trackhop`, add an alias: in PowerShell, `Set-Alias trackhop "$env:LOCALAPPDATA\Trackhop\trackhop-cli.exe"`
in your profile; in bash, `alias trackhop=/opt/trackhop/bin/trackhop-cli` in `~/.bashrc`.

Trackhop has to be running: Desktop next to the clock, or the container. The command finds it
through a key Trackhop makes at each start and leaves in its data folder (`cli.json`, readable by
your user only), accepted from this computer only. When Trackhop is not running, the command says so.

On a server shared by [several people](docker#several-people-on-one-server), `--user NAME` (or
`TRACKHOP_USER`) picks whose Trackhop: `docker exec trackhop trackhop --user anna run --all`.
Without it, the admin's.

## Commands

**Syncs**

| Command | What it does |
|---|---|
| `trackhop syncs` (or `trackhop list`) | Every sync: its route, schedule, label and tags, last run and result, next run. `--tag genre=metal` lists only the syncs with that tag (`--tag genre`: that key, whatever its value; given twice, both are wanted), `--label Gym` those wearing a label, `--favorite` the starred ones. |
| `trackhop show <sync>` | One sync, with its last runs. |
| `trackhop run <sync>…` | Starts these syncs. `--wait` waits until they end and prints the result; `--anyway` runs one that stopped because its destination looked much smaller than before (you say the smaller one is right); `--confirm-removals` makes the removals a run held back, this once (see them first with `trackhop preview <sync>`; a scheduled run never makes them). |
| `trackhop run --all` | Starts every sync that is turned on (with `--wait` too). |
| `trackhop run --tag genre=metal` | Starts a group: the syncs with that tag that are turned on (with `--wait` too). `--label Gym` and `--favorite` choose the group the same way. |
| `trackhop new <service> <playlist> <service> [<playlist>]` | Makes a sync from a playlist (or a link to a public one) to a playlist; without the second playlist, a new one is made at the first run. Options below. |
| `trackhop edit <sync> [options]` | Changes a sync: only what you name. |
| `trackhop enable <sync>`, `trackhop disable <sync>` | Turns a sync's schedule on or off. |
| `trackhop delete <sync>` | Deletes a sync; its playlists stay as they are. |
| `trackhop move <sync> <position>` | Moves it in the list (the order of *Sync all* and of the widgets): a number from 1, or `up`, `down`, `top`, `bottom`. |
| `trackhop stop <sync>`, `pause`, `resume` | Stops, pauses or resumes a sync that is running. |
| `trackhop crowded` | Whether many syncs reach one service at about the same time (the service can turn some of them away). |
| `trackhop spread` | New times for those syncs, each a little after the one before; shown only, saved with `--apply`. |
| `trackhop preview <sync>` | What a run would add, remove, not find and want checked now, changing nothing. |

The options of `new` and `edit`: `--name NAME`; `--schedule manual|hourly|every_6h|daily|weekly` or
`--every MINUTES`; `--at 07:30`; `--days mon,fri` (or `1,5`); `--changes add|mirror|archive` (only
add, also remove what leaves the source, or keep every track ever seen); `--follows` (with mirror:
the source changes by itself, a daylist); `--color teal` or `--color '#12A594'` (`none` takes it off);
`--label Gym` (a label's name or id: the card takes its colour; `none` takes it off); `--tag genre=metal`
(or `--tag kids`, a key alone; as many as you like; on `edit` it adds or changes that tag, and
`--untag genre` takes one off); `--favorite` stars the sync (`edit … --unfavorite` takes the star off);
`--two-way` also makes the reverse sync (marked ↔) between two existing playlists of yours, so what
you add on either side reaches the other, and `--two-way-removals` lets removals travel too
(`edit … --one-way` deletes the reverse sync, `--no-two-way-removals` keeps the pair adding only).

```
trackhop new spotify "Discover Weekly" ytm --name "Discover Weekly" --schedule weekly --days mon --at 08:00 --changes archive
trackhop edit "Road trip" --every 180
trackhop edit "Road trip" --tag mood=gym --favorite
trackhop run --tag mood=gym --wait
trackhop new spotify "Road trip" tidal "Road trip" --two-way
trackhop run "Road trip" --confirm-removals --wait
```

A `<sync>` is its name, the start of its name, or its id. When a name could mean more than one sync,
the command lists them and asks you to say which.

**Runs and review**

| Command | What it does |
|---|---|
| `trackhop runs [<sync>]` | The runs, newest first (`--limit`, `--offset` for more). |
| `trackhop report <run>` | One run: what it added and removed, what it could not find or wants checked, its notes. |
| `trackhop review <sync>` | The newest run with something to review (or `--run ID`): the tracks not found, the matches to check, the tracks removed, numbered. |
| `trackhop review <sync> ignore <n>…` | Tracks not found, not searched for again (`all` for every one). |
| `trackhop review <sync> keep <n>…`, `discard <n>…` | A match to check is right, or wrong (then removed). |
| `trackhop review <sync> pick <n> <track>` | The track to add for one not found: `"Artist - Title"`, a link, or its id on the destination. |
| `trackhop review <sync> replace <n> <track>` | The right track for a match to check. |
| `trackhop review <sync> restore` | Puts back what the run removed. |

**Services and playlists**

| Command | What it does |
|---|---|
| `trackhop services` | The services you added, and whether they are connected. |
| `trackhop playlists <service>` | A service's playlists, with their ids. |
| `trackhop tracks <service> <playlist>` | A playlist's tracks. |
| `trackhop add <service> <playlist> <track>…` | Adds tracks. A track is `"Artist - Title"` (searched and matched as a sync does), a link from any service, or its id on that service. Tracks already there are not added again. |
| `trackhop remove <service> <playlist> <track>…` | Removes tracks, named the same way. |
| `trackhop create <service> <name>` | A new playlist. |
| `trackhop search <service> <words>…` | Looks for a track on a service, with how well each result matches. |
| `trackhop convert <service> <track>…` | The same tracks on another service: their ids there, and how sure each match is. |
| `trackhop duplicates <service> <playlist>` | The tracks a playlist has more than once; `--remove` takes the extra copies out (`--wait` to wait for the end). |
| `trackhop mbtags [<service>]` | The songs on your Navidrome, Jellyfin, Emby or Plex server without MusicBrainz tags (with them a sync finds the exact recording). Nothing is changed; `<service>` can be left out when only one such server is connected. |
| `trackhop releases <service> <playlist> [--days 7\|30\|90] [--upcoming]` | The new albums, EPs and singles of a playlist's artists (Deezer and MusicBrainz, no account), with the date and a link: released in the last 30 days, or `--days 7` / `90`; `--upcoming` adds what is announced. A first search takes about a second per artist. |
| `trackhop wanted [--format json\|csv\|m3u\|text\|lidarr] [--albums] [--all] [--sync S] [--service S] [--since 7d]` | The tracks of your playlists missing on your own server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy), each once, with the syncs that miss them. `--format` prints it as a file (`> list.csv`), `lidarr` as Lidarr's Custom List; `--albums` groups by album, `--all` shows the ticked ones too; `--sync`, `--service` and `--since` (`24h`, `7d`, `2026-11-01`) narrow it. |
| `trackhop setlists <service> <playlist>` | The latest setlist.fm setlists of a playlist's artists (date, venue, city, number of songs, link); `--json` adds the songs (encores, tapes, covers) and each setlist's tracks, ready to add to a playlist. Asked only now, cached a week. Needs a setlist.fm key: Settings › setlist.fm key, or `SETLISTFM_API_KEY`. |
| `trackhop concerts <service> <playlist> [--country IT]` | The upcoming concerts of a playlist's artists in a country (date, city, venue, link), from Ticketmaster and MusicBrainz; the artists without dates are named (`--json` for their search links). Asked only now, cached a day. Ticketmaster needs a key: Settings › Ticketmaster key, or `TICKETMASTER_API_KEY`. |
| `trackhop export <service> <playlist> [<file>]` | A playlist as a file: `--format csv`, `m3u`, `xspf`, `jspf` or `txt` (or from the file's extension); to the screen without `<file>`. |

A `<service>` is its name or id (`spotify`, `tidal`, `subsonic`, `csv` for File…, as `trackhop services`
shows); a `<playlist>` its name or id.

**Files and backups**

| Command | What it does |
|---|---|
| `trackhop files` | The playlist files of the File service. |
| `trackhop files get <name> [<file>]` | One, in a format (`--format`); to the screen without `<file>`. |
| `trackhop files rm <name>…` | Deletes them. |
| `trackhop files zip <file.zip> [<name>…]` | Several (all without names) in one .zip, in a format. |
| `trackhop import <file>` | A playlist file into the File service: CSV, M3U, PLS, XSPF, JSPF, iTunes XML, JSON or text. |
| `trackhop patterns` | How track names in imported files are read, and your own patterns ([how they work](export-playlist-csv#and-back-in)). |
| `trackhop patterns set <pattern>…`, `patterns clear` | Your own patterns, tried in order (each in quotes: `'{artist}/{album}/{track} {title}'`), or back to automatic. |
| `trackhop patterns layout <layout>` | `auto`, `artist_title`, `artist_album_title` or `custom`. |
| `trackhop patterns try <line>` | Reads a line or a path from a file with your patterns (or with `--pattern P`, repeated): what is the artist, the album, the title. Saves nothing. |
| `trackhop backup` | Backs up every playlist now (`--wait` to wait for the end). |
| `trackhop config [<file>]` | Your whole set-up as one file, as Backup & restore makes it; to the screen without `<file>`. |
| `trackhop config import <file>` | Brings a set-up back in: syncs with the same id are replaced, the others added. `--dry-run` only says what the file holds. |

**Notifications**

| Command | What it does |
|---|---|
| `trackhop notify` | The [notification channels](docker#notifications), their tokens masked. |
| `trackhop notify kinds` | The kinds of channel (Telegram, ntfy, Gotify, Discord, Slack, Matrix, Pushover) and their settings. |
| `trackhop notify add <kind> --set key=value…` | Adds a channel: `--name`, `--set` for each setting (`--set topic=trackhop-…`), `--events changes,failed,review,removals`. |
| `trackhop notify edit <channel> …` | Changes one; a token left out keeps its value. |
| `trackhop notify on <channel>`, `off`, `rm` | Turns it on or off, or removes it. |
| `trackhop notify test <channel>` | Sends a test message through a channel, named by its name or id; fails (exit 1) with the service's answer if it did not arrive. |
| `trackhop webhook` | The [webhook](docker#api): its address, whether it is signed, its events. |
| `trackhop webhook set <url>` | Sets it: `--secret S` to sign, `--events sync.started,sync.finished,sync.failed,backup.finished`. |
| `trackhop webhook off` | Turns it off. |

**Other**

| Command | What it does |
|---|---|
| `trackhop status` | Version, syncs, what is running. |
| `trackhop stats` | Your numbers: tracks moved, time saved, busiest route, top artists. |
| `trackhop settings`, `settings set <key> <value>` | The settings: `theme` (`system`, `light`, `dark`), `backupSchedule` (`manual`, `daily`, `weekly`), `backupFolder`, `updatesCheck`. |
| `trackhop tasks`, `trackhop task <id>` | The background tasks (backups, duplicates) and one of them. |
| `trackhop log` | The server's last log lines (`--lines N`); the admin's only on a shared server. |
| `trackhop diagnostics` | A report for asking for help: version, system, services, syncs, the last runs and the log, without secrets. `--since 24h` (or `90m`, `7d`, `2w`), `--names` keeps the names of syncs, playlists and tracks, `--out <file>` saves it. The admin's. |
| `trackhop report` | A GitHub issue made ready from that report (`--title`, `--description`, `--since`, `--names`) and the link that opens it prefilled. Nothing is sent. If the same error is already reported, it says where to comment instead, or to update. (`trackhop report <run>` is one run's result.) |
| `trackhop help [<command>]` | Every command, or all about one (`trackhop review --help` too). |
| `trackhop completion bash\|zsh\|fish` | Tab completion for your shell (below). |
| `trackhop --version` | The version. |

## In scripts

- `--json` prints what the API answers, for `jq` or your own code, with every command.
- Options take their value after a space or an `=` (`--every=180`); after `--`, every word is a
  word, even one that starts with dashes.
- The exit code: `0` done, `1` failed (a sync that ended with an error, a call refused, a sync or
  playlist not found), `2` wrong use, `3` no Trackhop reached.

```
# Every night at 3, in cron: run everything and keep a log.
0 3 * * * docker exec trackhop trackhop run --all --wait >> /var/log/trackhop.log 2>&1
```

## Tab completion

`trackhop completion` prints what your shell needs to complete the commands and options with Tab:

```
trackhop completion bash > /etc/bash_completion.d/trackhop        # bash (or source it from ~/.bashrc)
trackhop completion zsh > "${fpath[1]}/_trackhop"                 # zsh
trackhop completion fish > ~/.config/fish/completions/trackhop.fish
```

## A server elsewhere

To drive a Trackhop server from another computer, make a token on its page (**Tools › API**) and
give the command its address and the token:

```
trackhop --url https://trackhop.example.org --token th_… syncs
```

or set `TRACKHOP_URL` and `TRACKHOP_TOKEN`. The command is the same program as on the server, so
any Trackhop Desktop has it; or call the [API](docker#api) directly.

## With an AI assistant (MCP)

Trackhop speaks the [Model Context Protocol](https://modelcontextprotocol.io), so an AI assistant
(Claude Desktop, Claude Code, or any other MCP client) can use it on your word: "which of my syncs
failed this week?", "run Road trip and tell me what it couldn't find", "add these five songs to my
Focus playlist on Spotify". The assistant sees what you see in Trackhop and does only what these
tools allow:

| Tool | What it does |
|---|---|
| `status`, `get_stats` | Version, syncs, what is running; your numbers |
| `list_syncs`, `get_sync` | The syncs, their schedule, labels and tags, and their last results; `list_syncs` can list only those with a tag, a label or a star |
| `run_sync`, `run_all_syncs`, `run_syncs_by_tag` | Run one, all those turned on, or a group: "sync the playlists tagged gym" (by tag, label or star; optionally waiting for the result). `run_sync` can confirm the removals a run held back (`confirm_removals`) |
| `create_sync`, `edit_sync`, `move_sync` | Make a sync, change it (name, timing, removals, colour, label, tags, star, two-way), move it in the list |
| `set_sync_enabled`, `stop_sync`, `pause_sync`, `resume_sync` | Turn a sync's schedule on or off; stop, pause or resume a run |
| `preview_sync` | What a run would do now, changing nothing |
| `list_runs`, `get_run`, `get_review` | The runs, one run's tracks and notes, what it left to review |
| `list_services`, `list_playlists`, `get_playlist_tracks` | Services, playlists, tracks |
| `search_tracks`, `convert_tracks` | Look for a track on a service; find tracks on another service |
| `create_playlist`, `add_tracks` | A new playlist; tracks found and added ("Artist - Title", a link, an id) |
| `find_duplicates`, `find_untagged_songs`, `export_playlist` | A playlist's duplicates; the songs on your own server without MusicBrainz tags; a playlist as a file's text |
| `find_new_releases` | The new albums, EPs and singles of a playlist's artists (7, 30 or 90 days, and what is announced) |
| `list_missing_on_server` | The tracks of your playlists missing on your own server, each once ("what should I buy for my Navidrome?"); also as CSV, M3U, text or a Lidarr list |
| `find_setlists` | The latest setlists of a playlist's artists from setlist.fm, with the songs ready to make one a playlist ("make me a playlist of what Muse played last week") |
| `find_concerts` | The upcoming concerts of a playlist's artists in a country, with ticket links ("is anyone from my road-trip playlist playing in Italy?") |
| `list_files`, `get_file_patterns`, `set_file_patterns`, `try_file_patterns` | The File playlists, and how names in imported files are read |
| `get_settings`, `change_setting` | The settings |
| `list_notification_channels`, `test_notification` | The notification channels, and a test message |
| `list_tasks`, `get_task` | The background tasks |
| `get_crowded_syncs`, `spread_syncs` | Many syncs reaching one service at once, and new times for them (saved only with `apply`, after asking you) |
| `get_diagnostics`, `prepare_issue` | The report for asking for help, so the assistant can read the log and explain an error; a GitHub issue made ready for you to send (never sent by Trackhop; an assistant with its own GitHub tool creates it only after you confirm) |
| `backup_playlists` | Back up every playlist now |
| `remove_tracks`, `review_tracks`, `remove_duplicates`, `delete_sync`, `delete_files` | Changes that remove something (marked as destructive: assistants ask before) |

**On the same computer** (Trackhop Desktop, or Docker on this machine), the assistant starts the
command itself, `trackhop mcp`. In Claude Desktop, *Settings › Developer › Edit config*, add:

```json
{
  "mcpServers": {
    "trackhop": { "command": "/opt/trackhop/bin/trackhop-cli", "args": ["mcp"] }
  }
}
```

with the command's path from [the table above](#where-it-is) (on Windows, write the backslashes twice:
`"C:\\Users\\you\\AppData\\Local\\Trackhop\\trackhop-cli.exe"`). For Docker on this machine:
`"command": "docker", "args": ["exec", "-i", "trackhop", "trackhop", "mcp"]`. In Claude Code:
`claude mcp add trackhop -- /opt/trackhop/bin/trackhop-cli mcp`. Trackhop has to be running when the
assistant uses a tool; it can be started after the assistant.

**A server elsewhere**: the server answers MCP at `https://your-server/mcp` (Streamable HTTP), with
the API's token from **Tools › API** sent as `Authorization: Bearer th_…`. In Claude Code:
`claude mcp add --transport http trackhop https://your-server/mcp --header "Authorization: Bearer th_…"`.
Or run the command locally against it: `"args": ["--url", "https://your-server", "--token", "th_…", "mcp"]`.

The token is as good as your password for Trackhop: give it only to assistants you trust. Nothing
goes to the assistant unless it calls a tool, and Trackhop sends nothing anywhere by itself.
