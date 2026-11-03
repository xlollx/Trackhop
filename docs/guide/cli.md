---
title: Sync playlists from the command line (the trackhop command)
description: Run your playlist syncs, list them and add or remove tracks from a terminal, a script or a scheduled task, with the trackhop command of Trackhop Desktop and the Docker server. Free and open source.
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
| `trackhop syncs` | Every sync: its route, schedule, last run and result, next run. |
| `trackhop show <sync>` | One sync, with its last runs. |
| `trackhop run <sync>…` | Starts these syncs. `--wait` waits until they end and prints the result. |
| `trackhop run --all` | Starts every sync that is turned on (with `--wait` too). |
| `trackhop enable <sync>`, `trackhop disable <sync>` | Turns a sync's schedule on or off. |

A `<sync>` is its name, the start of its name, or its id. When a name could mean more than one sync,
the command lists them and asks you to say which.

**Services and playlists**

| Command | What it does |
|---|---|
| `trackhop services` | The services you added, and whether they are connected. |
| `trackhop playlists <service>` | A service's playlists, with their ids. |
| `trackhop tracks <service> <playlist>` | A playlist's tracks. |
| `trackhop add <service> <playlist> <track>…` | Adds tracks. A track is `"Artist - Title"` (searched and matched as a sync does), a link from any service, or its id on that service. Tracks already there are not added again. |
| `trackhop remove <service> <playlist> <track>…` | Removes tracks, named the same way. |
| `trackhop create <service> <name>` | A new playlist. |

A `<service>` is its name or id (`spotify`, `tidal`, `subsonic`…, as `trackhop services` shows); a
`<playlist>` its name or id.

**Files and backups**

| Command | What it does |
|---|---|
| `trackhop files` | The playlist files of the File service. |
| `trackhop import <file>` | A playlist file into the File service: CSV, M3U, XSPF, JSPF, iTunes XML, JSON or text. |
| `trackhop backup` | Backs up every playlist now (`--wait` to wait for the end). |
| `trackhop config [<file>]` | Your whole set-up as one file, as Backup & restore makes it; to the screen without `<file>`. |
| `trackhop notify` | The [notification channels](docker#notifications), their tokens masked. |
| `trackhop notify test <channel>` | Sends a test message through a channel, named by its name or id; fails (exit 1) with the service's answer if it did not arrive. |

**Other:** `trackhop status` (version, syncs, what is running), `trackhop help`, `trackhop --version`.

## In scripts

- `--json` prints what the API answers, for `jq` or your own code.
- The exit code: `0` done, `1` failed (a sync that ended with an error, a call refused, a sync or
  playlist not found), `2` wrong use, `3` no Trackhop reached.

```
# Every night at 3, in cron: run everything and keep a log.
0 3 * * * docker exec trackhop trackhop run --all --wait >> /var/log/trackhop.log 2>&1
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
| `status` | Version, syncs, what is running |
| `list_syncs`, `get_sync` | The syncs, their schedule and their last results |
| `run_sync`, `run_all_syncs` | Run one, or all those turned on (optionally waiting for the result) |
| `set_sync_enabled` | Turn a sync's schedule on or off |
| `list_services`, `list_playlists`, `get_playlist_tracks` | Services, playlists, tracks |
| `create_playlist`, `add_tracks` | A new playlist; tracks found and added ("Artist - Title", a link, an id) |
| `remove_tracks` | Tracks removed from a playlist (marked as destructive: assistants ask before) |
| `backup_playlists` | Back up every playlist now |

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
