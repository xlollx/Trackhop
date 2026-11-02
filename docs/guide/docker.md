# Trackhop on a server (Docker, beta)

> **Beta.** The server works for everyday use (it has run scheduled syncs for days) but is still
> young: pages and settings can change between releases. Keep a copy of the data volume (or save the
> set-up from *Tools › Backup & restore*) and [report](reporting-a-problem) what goes wrong. The
> unofficial connectors below stay an alpha.

The same sync engine as the Android app, running on a home server, a NAS or a small VPS, with a
web page to connect services, create syncs and see how they went. Scheduled syncs run there,
whether or not your phone is on.

## Start it

```sh
docker run -d --name trackhop \
  -p 8080:8080 \
  -v trackhop-data:/data \
  -e SONGPORT_URL=http://127.0.0.1:8080 \
  -e SONGPORT_PASSWORD=choose-one \
  ghcr.io/xlollx/trackhop:latest
```

Then open `http://127.0.0.1:8080` (any user name, the password you chose).

With Docker Compose:

```yaml
services:
  trackhop:
    image: ghcr.io/xlollx/trackhop:latest
    container_name: trackhop
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - ./trackhop-data:/data
    environment:
      SONGPORT_URL: http://127.0.0.1:8080
      SONGPORT_PASSWORD: choose-one
      # SONGPORT_LOCALE: it
```

The same image is also on [Docker Hub](https://hub.docker.com/r/xlollx/trackhop) as `xlollx/trackhop`
(`docker pull xlollx/trackhop`, the same `latest` and version tags), for tools and NAS app stores that
only search there. Either name works; the commands in this guide use `ghcr.io/xlollx/trackhop`.

The image runs on `amd64` and `arm64` (Raspberry Pi 4/5, most NAS). To build it yourself, from the
repository root: `docker build -f server/Dockerfile -t trackhop .`

**Coming from Songport?** Trackhop was called Songport up to version 1.1. The old image name,
`ghcr.io/xlollx/songport`, still works and gets every release with the same tags as
`ghcr.io/xlollx/trackhop`, so an existing setup keeps updating as it is. To switch, change only the
image name: keep your volume (or folder) and your settings. The variables keep their `SONGPORT_`
prefix and the log file its name `songport.log`, so nothing else needs to change.

## Update

The page says when a new version is out (**Settings › Updates**): twice a day at most it asks
GitHub's public API which version is the latest, nothing else. Turn it off there, or for everyone with
`SONGPORT_UPDATE_CHECK=false`.

The image is `ghcr.io/xlollx/trackhop:latest`, or a version (`:0.7.0`): Docker and Trackhop Desktop
have their own version, separate from the app's, listed in the
[Desktop & Server releases](https://github.com/xlollx/Trackhop/releases?q=server-v&expanded=true). To
update, pull the image and start the container again; everything is in the volume, so nothing is lost:

```sh
docker pull ghcr.io/xlollx/trackhop:latest
docker rm -f trackhop
docker run -d --name trackhop … ghcr.io/xlollx/trackhop:latest   # the same command as the first time
```

With Docker Compose: `docker compose pull && docker compose up -d`. The page's footer shows the
version running, and the app version whose engine it uses.

## Settings

| Variable | Default | What it does |
|---|---|---|
| `SONGPORT_URL` | `http://127.0.0.1:8080` | The address your browser uses to reach Trackhop. Sign-ins come back to `<SONGPORT_URL>/callback`. |
| `SONGPORT_PASSWORD` | none | Asks for this password on every page. The browser also asks for a user name: any will do, only the password counts. **Set it** whenever anyone else can reach the port. With `SONGPORT_MULTIUSER`, the admin's password. |
| `SONGPORT_MULTIUSER` | off | `true`: [several people](#several-people-on-one-server), each with their own services and syncs, signing in with a user name and a password. Needs `SONGPORT_PASSWORD`. |
| `SONGPORT_LOCALE` | English | Language of the texts that come from the engine: `it`, `de`, `fr`, `es`, `pt-BR`, `pl`, `ru`, `tr`, `zh`, `ja`, `ko`. |
| `SONGPORT_PORT` | `8080` | Port inside the container. |
| `SONGPORT_DATA` | `/data` | Where everything is kept (the volume). |
| `SPOTIFY_CLIENT_ID`, `TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY` | none | Your keys, if you'd rather pass them here than type them in the page. |
| `DEEZER_REDIRECT_URL` | `<SONGPORT_URL>/callback` | Deezer's redirect, which must be `https`. |

Everything lives in the `/data` volume: `store.json` (syncs, reports, match cache, settings),
`tokens.json` (sign-ins, readable only by the container's user), `songport.log`, and `csv/` for
playlist files. Back that folder up and you have backed up Trackhop.

## Services

| Service | On the server | What you need |
|---|---|---|
| Spotify | yes | Your own key from developer.spotify.com |
| YouTube | yes | Your own Google OAuth client (with its secret) |
| TIDAL | yes | Your own key from developer.tidal.com |
| Deezer | yes (beta) | Your own app, and an `https` address |
| Navidrome / Subsonic, Jellyfin, Plex | yes | The server's address and your login |
| Last.fm, ListenBrainz | yes, as sources | Your user name |
| SoundCloud | yes, as a source (unofficial) | Nothing: pick it as the source of a new sync and paste a public set or likes link. [More](soundcloud) |
| NetEase Cloud Music, QQ Music | yes, as sources (unofficial) | Nothing: pick one as the source of a new sync and paste a public playlist link. [More](netease-qq-music) |
| CSV files | yes | Put them in `csv/` inside the data volume |
| YouTube Music | yes (unofficial, alpha) | The cookies of a browser where you are signed in: [see below](#youtube-music-unofficial) |
| Spotify (web) | yes (unofficial, alpha) | The `sp_dc` cookie of a browser where you are signed in: [see below](#spotify-and-deezer-without-a-key-unofficial) |
| Deezer (web) | yes (unofficial, alpha) | The `arl` cookie of a browser where you are signed in: [see below](#spotify-and-deezer-without-a-key-unofficial) |
| Amazon Music, Apple Music | no | See below |

**Why not all of them.** The other quick routes (Amazon Music, Apple Music without a key) work by
running the service's own web player inside the phone's browser engine: there is no such browser
on a server. Apple Music's official route signs in with Apple's MusicKit JS, which needs one too.
Keep those syncs in the app.

## YouTube Music (unofficial)

The server talks to music.youtube.com the way the app's built-in YouTube Music connector does,
through the web player's own endpoints, signed in with the cookies of a browser where you are
signed in. It is **not an official API**: Google can change it or block it at any time, and it is
an **alpha** on the server.

**Copy the cookies** (a desktop browser, Chrome, Edge or Firefox):

1. Open [music.youtube.com](https://music.youtube.com) and sign in with the account to sync.
2. Open the developer tools (<kbd>F12</kbd>, or <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>I</kbd>; on a
   Mac <kbd>Cmd</kbd>+<kbd>Option</kbd>+<kbd>I</kbd>) and pick the **Network** tab.
3. Reload the page, type `browse` in the filter box and click one of the requests that appear (any
   request to `music.youtube.com` will do).
4. Under **Headers**, then **Request Headers**, find `cookie:`, right-click its value and copy it
   (in Firefox: *Raw* view, then select the value).
5. In Trackhop, **Services**, **YouTube Music (unofficial, alpha)**, paste it in *Cookies* and press
   **Connect**. The page checks them with one call before saving.

A `cookies.txt` export or the JSON export of a cookie-editor extension works too: paste the file's
content. Only YouTube's and Google's cookies are kept. Optionally paste your browser's user agent
as well (type `navigator.userAgent` in the developer tools' *Console*): requests then look like that
browser's.

**What to know before using it:**

- **They are as good as your password.** The cookies give full access to that Google account. They
  are stored only in the data volume (`tokens.json`, readable by its owner only) and never written
  to the log. Consider a separate Google account for syncing.
- **They end.** Signing out of that browser session, changing the password or Google rotating them
  ends the cookies: paste fresh ones. To keep them alive longer, copy them from a browser profile
  you then leave alone (a private window ends its cookies when it closes).
- **Google's block.** Without the app's hidden browser every call is plain HTTP, which Google's
  abuse detection ("Sorry… unusual traffic") stops sooner. When that happens Trackhop pauses
  YouTube Music for a few minutes, longer each time. Open music.youtube.com in a browser **on the same
  network as the server**, pass Google's check, then paste fresh cookies: that also lifts the pause.
- **Home beats datacenter.** Addresses of VPS and cloud providers are flagged far more often than a
  home connection: a server at home (a NAS, a Raspberry Pi) works best.

**Your own key.** The app's built-in keys only work with the app's own redirect (`songport://`),
so on the server every service that needs a key needs yours. Each service page in Trackhop lists
the steps and shows the exact redirect address to paste. The rules each service sets:

- **Spotify** accepts `http` only on `127.0.0.1`, otherwise `https`: use
  `SONGPORT_URL=http://127.0.0.1:8080` when you sign in from the server itself, or put Trackhop
  behind a reverse proxy with https.
- **Google (YouTube)**: create a *Web application* OAuth client, add `<SONGPORT_URL>/callback` as an
  authorized redirect URI, and paste both the client ID and the client secret. Google accepts `http`
  only for `127.0.0.1` and `localhost`.

Open the page at the same address as `SONGPORT_URL` (`127.0.0.1`, not `localhost`): the service
sends you back there after the sign-in. Spotify no longer accepts `localhost` at all.
- **Deezer** wants an `https` redirect.
- **TIDAL** takes the redirect you register.

Signing in from another computer than the server is fine, as long as `SONGPORT_URL` is the
address that computer's browser uses.

## Spotify and Deezer without a key (unofficial)

*Spotify (web)* and *Deezer (web)* talk to the web players' own backends, as the app's connectors of
the same name do, signed in with the cookies of a browser where you are signed in. **Not an
official API**: against the services' terms, they can stop working when the players change, and
they are an **alpha** on the server.

**Copy the cookie** (a desktop browser): sign in on [open.spotify.com](https://open.spotify.com)
or [deezer.com](https://www.deezer.com), open the developer tools (<kbd>F12</kbd>), then
**Application** (Firefox: **Storage**) › **Cookies** › `https://open.spotify.com` (or
`https://www.deezer.com`). Copy `sp_dc` for Spotify (with `sp_key` and `sp_t` when listed) or `arl`
for Deezer as `name=value`, several joined by `; `, and paste them in Trackhop, **Services**, in the
connector's *Cookies*. The `cookie:` header of a request, a `cookies.txt` or a JSON export work too.

The same cautions as YouTube Music apply: the cookies are as good as your password, and they end
when you sign out in that browser. Spotify's access token is read from the player page fetched with
the cookies: when that page answers as a signed-out visitor, Trackhop says the cookies have expired.
Paste fresh ones then.

## LiveOne (unofficial)

LiveOne (formerly Slacker Radio) has no public API: Trackhop goes through liveone.com's own web
player, unofficial and alpha, like the web connectors above. No developer key.

1. In a desktop browser, sign in to [liveone.com](https://www.liveone.com).
2. Open the developer tools (F12), then **Application** (**Storage** in Firefox) › **Local storage** ›
   `https://www.liveone.com`, and copy the value of **`refresh_token`** (a long text starting with `eyJ`).
3. In Trackhop, **Services › Add › LiveOne (web)**, paste it and press **Connect**.

Trackhop renews the session from that token as the player does; it lasts until you sign out of
LiveOne in that browser. It is as good as your password: keep it to yourself.

What works: a free account's **presets**, shown as the liked songs, as a source and as a target
(songs are added, not removed); search. Not yet: playlists of your own (they need LiveOne's paid
plan), removing songs, and stations as a radio source (LiveOne plays them only in the US).

## Radio syncs

A radio sync listens to an internet radio in the hours you choose and saves the songs it plays to a
playlist on any service: the morning show's music in your Spotify, a station's evening mix on
YouTube Music. On the home page, **New radio sync**:

1. **Find the station** by name (from [radio-browser.info](https://www.radio-browser.info), a free
   directory of some 50,000 radios), or paste its stream's address.
2. **Pick where the songs go**: a service, then a playlist or a new one.
3. **Pick the days and the hours.** The same time twice listens all day; an end before the start runs
   past midnight (Friday 22:00–02:00 listens into Saturday night).

While its window is open, Trackhop reads the title of each song the station sends with its stream
(Icecast and Shoutcast do, as most internet radios and the streams of FM stations); the audio itself
is read and dropped, never kept. Each new song is noted once in a File playlist of its own
(*Radio - <station>*), the station's announcements, adverts and news left out, and the sync takes
them to your playlist every 10 minutes and once more when the window closes, matched as any sync.
Its card wears a radio and says what is on air.

A station that sends no titles is reported on the card ("this stream sends no song titles"): try
another of its streams (an MP3 one rather than HLS), or another station. Listening uses the
station's bandwidth (128 kbps is about 60 MB an hour) on the server and on Trackhop Desktop, which
are always on. The Android app has radio syncs too: there the phone samples the stream every 2
minutes, on Wi‑Fi by default, instead of listening throughout ([guide](radio)).

## Several people on one server

One container can serve the whole household: each person signs in with their own user name and
password and has a Trackhop of their own, with their own services, syncs, files, backups and log.
Nobody sees the others'. Add `SONGPORT_MULTIUSER`:

```yaml
    environment:
      SONGPORT_URL: http://<NAS-IP>:8080   # example: http://192.168.1.10:8080
      SONGPORT_PASSWORD: choose-one        # the admin's password
      SONGPORT_MULTIUSER: "true"
```

1. Open the page and sign in as **admin**, with `SONGPORT_PASSWORD`.
2. In **Users**, add each person with a user name, a first password and what they may use (below).
3. They sign in at the same address with their name and password, and connect their own services.
   The 👤 link with their name, in the top bar, is where they change their password or sign out.

- **What each person may use**, ticked by the admin in **Users** and changed there at any time:
  - *Co-admin*: manages the others as the admin does (not themselves: another admin does that).
  - *Unofficial connectors*: YouTube Music, Spotify and Deezer with browser cookies; NetEase, QQ
    Music and SoundCloud links. Off, they are not even offered. A service may limit an account that
    uses them, so it is the admin's call.
  - *Radio syncs*: the server listens to a station for hours, with its bandwidth.
  - *Folders on the server*: backups copied to a folder of the server.
  - *API and command line*: the API's token, the webhook and the `trackhop` command.

  A new person gets everything but co-admin; people added with 0.6.0 too. A change restarts that
  person's Trackhop (a sync running at that moment starts again at its next turn). Each person sees
  what they may use under their name.
- **Forgotten passwords.** Someone else's: the admin (or a co-admin) sets a new one in **Users** ›
  *Forgot the password?*; they are signed out everywhere and can change it afterwards. The admin's:
  it is `SONGPORT_PASSWORD` in the container's settings (the Compose file, or the container's
  environment in Portainer, Unraid, Synology…): read it there, or set a new one and restart the
  container. Nothing else changes, and the admin's old sessions end.
- **What was there stays the admin's.** A server used by one person before carries on as the
  admin's Trackhop: same services, same syncs. The others start empty.
- **Scheduled syncs run for everyone**, signed in or not.
- **Data**: each person's is in `/data/users/<name>`, the admin's in `/data` as before. Removing
  someone stops their Trackhop and moves their folder to `/data/users-removed`, to delete when you
  no longer need it.
- **Sign-ins to the services** come back to the person who started them, through the same
  `SONGPORT_URL`: nothing to set per person. With your own Spotify or Google keys, they are shared by
  everyone on the server; Spotify's own limits on how many people a key in development mode serves
  apply.
- **The command line** works for each person: `docker exec trackhop trackhop --user anna status`
  (without `--user`, the admin's). The API's token is per person too (Tools › API), and says whose
  Trackhop answers.
- **Backups to a folder**: each person picks a folder of their own, such as `/backups/anna`.
- Each person's Trackhop takes some memory of its own: fine for a household, not meant for strangers
  sharing one server.

Without `SONGPORT_MULTIUSER` nothing changes: one Trackhop, one password, any user name.

## Behind a reverse proxy

Point the proxy at port 8080 and set `SONGPORT_URL` to the public `https://` address. Trackhop
needs nothing else: no websockets, no special headers. `/health` answers `ok` without a password,
for health checks.

## On your phone, as an app

Trackhop's page can sit on your phone's home screen and open full screen, like an app (a PWA):
the syncs keep running on the server, so it works with the phone off too.

- **iPhone, iPad**: open the page in Safari, *Share › Add to Home Screen*. The first time it opens
  from the home screen it asks for the password again (it keeps its own sign-in).
- **Android**: open the page in Chrome. With an `https://` address (a [reverse proxy](#behind-a-reverse-proxy),
  Tailscale…), *⋮ › Install app*; on a plain `http://192.168.…` address Chrome only offers
  *⋮ › Add to Home screen*, a shortcut that opens in the browser.

It shows the server's pages live, nothing is kept on the phone; if the server is off or out of
reach, it says so. Connect the services (Spotify and the others) from a browser: their sign-in can
leave the home-screen app and not come back to it.

## How it runs

- Scheduled syncs (hourly, every 6 hours, daily, weekly) start on their own; the page also has
  *Run now*, *Pause* and *Stop*.
- A scheduled run whose two sides are unchanged since last time is skipped, as in the app.
- A scheduled run that finds no network is retried after 15 minutes.
- The log is in the page, in `docker logs trackhop` (or the name you gave the container), and in
  `songport.log`.

## Tools

The page's **Tools** are the app's: Export a playlist, File playlists (upload any format, download
CSV, M3U, XSPF, JSPF or text), **Full backup** (by hand, daily or weekly, optionally copied to a
folder: mount one, for example `-v /mnt/nas/trackhop:/backups`, and enter `/backups`),
**Duplicates**, **Unplayable tracks**, **AI playlists** and, on Docker only, **[Lyrics](#lyrics)**. Long runs go on in the background: their
page refreshes until the result, and you can leave it.

## Lyrics

Trackhop can give a music library on the server its lyrics: for each audio file without them, it
asks [LRCLIB](https://lrclib.net), a free archive written by its users, and saves what it finds next
to the file, synced lyrics as `<name>.lrc` and plain ones as `<name>.txt`. Navidrome, Jellyfin and
Plex show them in their players after their next library scan. Only on Docker, not in the app or in
Trackhop Desktop.

Mount the music folder and name it in `SONGPORT_MUSIC`:

```yaml
services:
  trackhop:
    image: ghcr.io/xlollx/trackhop:latest
    volumes:
      - ./trackhop-data:/data
      - /path/to/music:/music
    environment:
      SONGPORT_MUSIC: /music
```

The container runs as user 999, so it needs to be allowed to write in that folder (and only to add
files; it changes none): `sudo setfacl -R -m u:999:rwX -m d:u:999:rwX /path/to/music` gives it that
without changing the files' owner. Do not mount it `:ro`.

Then **Tools › Lyrics** looks through the whole library or one folder of it. The artist, title,
album and length come from the files' tags (or from names like `Artist - Title.mp3` when a file has
none); a title with "Remastered", "(feat. …)" or "(with …)" is tried again without them, and the
length has to match within a few seconds, so a live version does not get the studio lyrics.

- Files that already have lyrics, beside them or in their tags, are left as they are; nothing is
  overwritten.
- A file LRCLIB does not know is asked again after a month, or now with *Ask again*. Anyone can add
  the missing ones on lrclib.net.
- *Every week* looks only at what has no lyrics yet, so new albums get theirs on their own.
- *Synced lyrics only* skips the plain ones.

For each file, its artist, title, album and length are sent to LRCLIB, nothing else. Lyrics are
protected by copyright: they are for your own listening. On a server shared by several people,
only the admin sees this tool.

## AI playlists

Describe a playlist; your own AI writes it and Trackhop creates it on a service (or saves it into
File). Trackhop has no AI of its own and sends the AI only your description. Free ways:

- **A free key**: [OpenRouter](https://openrouter.ai/keys) with a model whose name ends in `:free`,
  [Groq](https://console.groq.com/keys), or [Google Gemini](https://aistudio.google.com/apikey).
- **A model on your own machine, no key at all**: [Ollama](https://ollama.com) next to Trackhop.
  With Docker Compose:

  ```yaml
  services:
    trackhop:
      image: ghcr.io/xlollx/trackhop:latest
      ports: ["8080:8080"]
      volumes: ["./trackhop-data:/data"]
      environment:
        SONGPORT_PASSWORD: choose-one
    ollama:
      image: ollama/ollama
      volumes: ["ollama:/root/.ollama"]
  volumes:
    ollama:
  ```

  Then `docker compose exec ollama ollama pull llama3.2` (about 2 GB; a bigger model knows more
  music but needs more memory), and in **Tools › AI playlist** choose *OpenAI-compatible*, address
  `http://ollama:11434/v1`, model `llama3.2`. With Trackhop Desktop, install Ollama on the computer
  and use `http://localhost:11434/v1`.

What the AI proposes is searched on the service like any sync, so a track it made up is simply not
found.

## API

Other tools (Home Assistant, n8n, Node-RED, a script) can list the syncs, start them and read how
they went. Turn it on in **Tools › API**: make a token (shown once; only its hash is kept) and send
it as `Authorization: Bearer <token>`. It is separate from the page's password; on Trackhop Desktop
only the computer itself can reach it. From a terminal or a script, the [`trackhop` command](cli)
does the same with less typing: `docker exec trackhop trackhop run --all`, no token needed inside
the container.

| Call | What it does |
|---|---|
| `GET /api/v1/status` | Version, number of syncs, the ones running, the last run's time |
| `GET /api/v1/syncs` | Every sync: source, target, schedule, running, last result |
| `GET /api/v1/syncs/<id>` | One sync and its last 20 runs |
| `POST /api/v1/syncs/<id>/run` | Starts it (202) |
| `POST /api/v1/syncs/run-all` | Starts every enabled sync |
| `GET /api/v1/runs/<id>` | A run: added, removed, not found, with the tracks |
| `GET /api/v1/services` | The services and whether they are connected |
| `GET /api/v1/files`, `GET /api/v1/files/<name>.csv?format=m3u` | The File playlists, in any export format |
| `POST /api/v1/files/<name>` | Adds a playlist file sent as the body (CSV, M3U, XSPF, JSPF, iTunes XML, JSON or text), ready to sync (201) |
| `POST /api/v1/syncs/<id>/enable`, `…/disable` | Turns a sync on or off |
| `GET /api/v1/backup`, `POST /api/v1/backup` | The last full backup and its schedule; starts one (202, with a task id) |
| `GET /api/v1/tasks/<id>` | A background task (a backup, lyrics): progress, done, error |
| `GET /api/v1/lyrics`, `POST /api/v1/lyrics` | The music folder and the last lyrics run (files, saved, synced, plain, not found); starts one (202, with a task id): `?folder=Queen/Jazz` for one folder, `&synced=true` for synced lyrics only, `&retry=true` to ask again for the ones not found. [Lyrics](#lyrics) |
| `GET /api/v1/config` (`?history=true`) | The whole set-up as a `.trackhop` file, as *Tools › Backup & restore* saves it |
| `GET /api/v1/metrics` | Prometheus metrics: syncs, running, and per sync the last run's time, result, added, removed and not found |
| `GET /api/v1/services/<service>/playlists` | A connected service's playlists (and its liked songs, saved albums… where it has them) |
| `POST /api/v1/services/<service>/playlists` | Creates a playlist: `{"name": "…"}` (201, with its id) |
| `GET /api/v1/services/<service>/playlists/<id>/tracks` | The playlist's tracks: id, title, artists, album, duration, ISRC |
| `POST /api/v1/services/<service>/playlists/<id>/tracks` | Adds tracks, searched and matched as a sync does: `{"tracks": [{"title": "…", "artist": "…"}, {"link": "https://open.spotify.com/track/…"}, {"id": "…"}, "Artist - Title"]}`. Tracks already there are skipped (`"allowDuplicates": true` to add them anyway); the answer lists what was added, already there and not found |
| `DELETE /api/v1/services/<service>/playlists/<id>/tracks` | Removes tracks named by id, or by title and artist matched against the playlist (`POST …/tracks/remove` does the same, for tools that cannot send a body with DELETE) |
| `PATCH`, `DELETE /api/v1/services/<service>/playlists/<id>` | Renames (`{"name": "…"}`) or deletes a playlist, where the service allows it |
| `GET /health` | `ok`, with no token: for Docker's health check, Uptime Kuma or a reverse proxy |

```sh
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/syncs
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/syncs/run-all
```

**Webhook.** With an address set in Tools › API, every finished run is POSTed there as JSON
(`"event": "sync.finished"`, the sync, and the run with its added and not-found tracks), and every
full backup as `"event": "backup.finished"` (services, failures). With a secret, `X-Trackhop-Signature: sha256=<hex>` is the HMAC-SHA256 of the body. In Home Assistant, a
*Webhook* trigger with that address starts an automation (a notification when tracks were not
found, say); a `rest_command` that POSTs to `/api/v1/syncs/run-all` starts every sync from one.

**Prometheus and Grafana.** `/api/v1/metrics` speaks Prometheus' text format; a scrape job with the
token is enough:

```yaml
scrape_configs:
  - job_name: trackhop
    metrics_path: /api/v1/metrics
    bearer_token: th_your_token
    static_configs:
      - targets: ["trackhop:8080"]
```

**Playlists and tracks.** `<service>` is an id from `GET /api/v1/services` (`spotify`, `ytm`,
`navidrome_1`…). A Home Assistant button that adds the song playing in the kitchen to a playlist,
or a script that collects the songs a radio played:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tracks": ["Daft Punk - Digital Love", {"link": "https://open.spotify.com/track/2VEZx7NWsZ1D0eJ4uv5Fym"}]}' \
  http://127.0.0.1:8080/api/v1/services/ytm/playlists/PLxxxxxxxx/tracks
```

**Scripts.** Push a playlist file and sync it, or save the set-up every night:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary @mix.m3u http://127.0.0.1:8080/api/v1/files/mix.m3u
curl -H "Authorization: Bearer $TOKEN" -o trackhop-$(date +%F).trackhop http://127.0.0.1:8080/api/v1/config
```

## Not there yet

The connectors that run a service's own web player (Amazon Music, Spotify and Apple Music without a
key, Deezer web, SoundCloud with an account) need a browser inside Trackhop: they are the next step.
The compatibility card and the playlist poster are not on the page yet either.

[Back to the guide](./)
