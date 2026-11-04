# Trackhop Desktop & Server: changes

Docker (`ghcr.io/xlollx/trackhop`) and Trackhop Desktop share this version (`server/VERSION`) and are
released together as `server-v<version>`. The app keeps its own (`v1.x`), and the engine each
server version runs is the app's of the same commit, shown in the page footer. Up to the app's 1.3.8
the Docker image carried the app's version.

## 1.7.0

- **Keep every track (archive)**: a sync whose target only grows, for playlists that change by themselves (Discover Weekly, daylist, charts). The sync forms ask what to do when the source changes: only add, copy the latest version, or keep every track. API `archive` and `followsChanges` fields; `trackhop show` says it.
- Syncs between two playlists of the same account (for example Discover Weekly into a playlist of yours) take the tracks as they are, with no search.
- TIDAL: tracks listed without their artists are asked for again with them.

## 1.6.0

- **Playlist generator with no AI key**: from artists you pick or one of your playlists, with ListenBrainz/MusicBrainz data (Deezer's public catalogue fills in an artist's top tracks). Tools › AI playlists.

- Spotify liked songs keep the source's order (saved one a second; a big first sync says it will be slow).
- YouTube (Google API): an add refused with "operation aborted" is tried again.

## 1.5.0

- **Qobuz** (alpha, unofficial): your playlists and favourite tracks, read and written, through play.qobuz.com's own web player; paste a request of a signed-in browser. [How](https://trackhop.org/guide/docker#qobuz-unofficial)

## 1.4.0

- **Charts as a source**: Apple Music's Top 100 of a country and Deezer's charts (by country and worldwide), from their public feeds, no account: a sync keeps a playlist of yours up to date as the chart changes.
- **TikTok "original sound"**: when a video's page names no song, Trackhop listens to the sound and often finds the song in it (an audio signature, as SongRec does; not in the Microsoft Store build).

- **Notifications** (Settings › Notifications): Telegram, ntfy, Gotify, Discord, Slack, Matrix and Pushover, any number at once, each with its events (changes, a failed sync or an expired sign-in, tracks to review, removals waiting) and a *Send test* button; tokens masked and kept out of the set-up file; `trackhop notify` and `/api/v1/notifications`. [How](https://trackhop.org/guide/docker#notifications)
- Spotify: Liked Songs, saved albums, followed artists and podcasts written through Spotify's new library address (the old one is closed to personal apps since February 2026); a target refusing every addition stops the sync with its answer instead of "not found".
- Amazon Music: the region of the account's own store (an amazon.nl account on music.amazon.com).
- A source that renames itself often (a Spotify daylist) no longer renames the sync each time.

## 1.3.0

- **MCP**: an AI assistant (Claude Desktop, Claude Code, any MCP client) can list, run and turn on or off your syncs, read playlists, create them and add or remove tracks: `trackhop mcp` on the same computer, or `/mcp` on the server with the API's token. [How](https://trackhop.org/guide/cli#with-an-ai-assistant-mcp)
- Deezer's own mark next to its name.

## 1.2.0

- A search that fails is no longer counted as "not found": many failures stop the search with the reason, in the log too.
- Spotify searches go at a steady pace: a first sync of many songs into Spotify (from YouTube, with no ISRC) no longer runs into Spotify's half-hour blocks (issue #30).
- Anghami: public playlists from a link, read-only, no account; and Anghami (web), unofficial and alpha: your playlists and liked songs, read and written, with the session of a browser signed in to Anghami.
- A source that renames itself (a Spotify daylist) renames the target too, unless you gave the target a name of your own; a new cover is copied too, where the target takes covers (Spotify) (issue #32).
- YouTube (Google API) Liked songs leave out Music videos that are not songs.
- Your own schedules: any interval from an hour to a week, a time of day, the days of the week.
- Emby (beta): your Emby server as a source and a target, like Jellyfin (playlists, favourites).
- "The source changes by itself" next to Mirror removals: for a daylist or Discover Weekly, big removals go through with no confirmation.
- A source playlist's description is copied as plain text (a Spotify daylist's is HTML, which YouTube refused).

## 1.1.0

- **Review several tracks at once**: tick tracks in the review page (or "Tick all"), then keep, remove
  or ignore them together. An uncertain match can now be **removed**: the wrong track leaves the
  playlist (where the service allows removals) and this sync no longer adds the song (issue #26).
- **Songs from TikTok**: paste a TikTok video's link in a review search and its song is found on the
  service, read from the video's public page. A creator's own sound names no song, and the page says
  so. Not in the Microsoft Store build.
- **License**: from this version Trackhop is under the **AGPL-3.0-only** (it was GPL-3.0-only): a
  modified Trackhop server offered to others over a network must offer them its source code too.
  The page footer links to the source code.

## 1.0.0

- **Trackhop Desktop is out of beta**, hence 1.0. The unofficial connectors stay an alpha; the server on Docker
  stays a beta for now.
- **Invitation links** for several people on one server: the admin picks a name and what they may use,
  and sends a link; the person opens it and chooses their own password. A link works once, for 7 days,
  and can be renewed or cancelled. Adding someone with a password chosen by the admin still works.
- **`SONGPORT_USER`**: the user name that goes with `SONGPORT_PASSWORD`. Set, only that name signs in
  (otherwise any name will do, as before); with several people, it is the admin's name instead of `admin`.
- **New-version notice**: it now sees the server's releases (0.8.0 missed them, being marked as pre-releases on GitHub).

## 0.8.0

- **Syncs writing to the same playlist** run one after the other, and skip removals there (each would
  take away what the other adds); a warning when saving a duplicate sync or a shared playlist with mirror on.
- **TIDAL** is out of beta.
- **LiveOne (web)**, unofficial and alpha: signed in with the web player's refresh token, pasted
  once; a free account's presets as liked songs (read and add), search. Not in the Microsoft Store build.
- **Home page side column** on a wide screen (not on a phone): tracks added in the last two weeks (chart), the
  numbers, the latest runs and the services in use. On the left by default; in **Settings › Home page**
  on the right or hidden, and which cards to show.
- **Radio syncs: Mediaset stations** (Virgin Radio, R101, Radio 105) send title and artist as a
  "~" record: they are now read as songs, and the card shows "Artist - Title".
- **Settings**: a new tab with the page's look (the system's, light or dark, kept for each person)
  and **updates**: a line under the header when a new version is out, with how to update. Twice a
  day at most Trackhop asks GitHub's public API; off in Settings or with `SONGPORT_UPDATE_CHECK=false`,
  and not done where the Microsoft Store, Flathub, Homebrew, Scoop or Chocolatey update Trackhop.
- **Lyrics: LRCLIB busy is waited out.** When LRCLIB answers "busy", Trackhop waits a few seconds
  and asks again before counting the file as not found for a week.

## 0.7.0

- **Lyrics for your music library** (Docker only): mount the music folder, set `SONGPORT_MUSIC`, and
  Tools › Lyrics saves the lyrics of each file next to it (`.lrc` synced, `.txt` plain) from LRCLIB,
  where Navidrome, Jellyfin and Plex show them. Nothing is overwritten; a weekly pass for new files;
  `GET`/`POST /api/v1/lyrics`.
- **Fewer tracks not found**: before searching, titles also lose credits ("(with X)", "(con X)",
  "(prod. X)"), localised remaster notes and "- <Album> Version"; "Don't" is searched as one word; an
  artist field holding several names is also searched by the first one; ListenBrainz tries the title
  without its decorations.
- **Navidrome/Subsonic, Jellyfin, Plex and Last.fm are out of beta**, used against real servers and
  accounts.
- The guide, the links in the page and the release notes point to the new site, **trackhop.org**.

## 0.6.3

- **ListenBrainz works as a target again**: its track lookup now wants the user token (and a new path),
  so every search failed; it sends the token, and when two syncs spend the rate-limit window together
  it waits for the window instead of ending in "429".
- **TIDAL**: saved albums and followed artists get the access they need (collection scopes), and the
  setup steps say which scopes to tick on developer.tidal.com, where a new app has them all off.
- **Last.fm** lists are named as on Last.fm ("Loved tracks"), with a new one: the most played tracks
  of the last month.
- **A source read empty** (no loved tracks yet, an emptied playlist) is said on the sync's card
  instead of a silent +0.
- **Jellyfin** users without a password can sign in.
- A connection dropped during the TLS handshake ("connection closed") is waited out and tried again.

## 0.6.2

- **A target read in part no longer makes a sync write everything again**: when a service lists far
  fewer tracks than the last run left on the target (under half, from 20 tracks: YouTube Music once
  sent a few songs of a Liked music of thousands), the target is read again and, still that small,
  the run stops before writing anything, with both counts in the error. If the playlist really was
  emptied, **Sync anyway** on the sync's card runs it once regardless; scheduled runs never do.
- **YouTube Music**: a playlist listing under half of the song count in its own header is refused
  instead of taken as the whole.

## 0.6.1

- **What each person may use**, on a server shared by several people: in **Users** the admin ticks,
  for each person, co-admin (manages the others), the unofficial connectors, radio syncs, folders on
  the server and the API with the command line. A new person gets everything but co-admin, and so do
  the people added with 0.6.0. Each person sees theirs under their name.
  [How](https://trackhop.org/guide/docker#several-people-on-one-server).
- **A forgotten password**: the Users page sets a new one for anyone (*Forgot the password?*), and
  says where the admin's own is (`SONGPORT_PASSWORD` in the container's settings).
- Trackhop Desktop: no changes.

## 0.6.0

- **Several people on one server** (Docker): with `SONGPORT_MULTIUSER=true` each person signs in
  with their own user name and password and has a Trackhop of their own, with their own services,
  syncs, files, backups and log; nobody sees the others'. The admin (`admin`, with
  `SONGPORT_PASSWORD`) adds and removes people in **Users** and keeps what the server had before.
  A real sign-in page with a sign-out, scheduled syncs for everyone, `trackhop --user <name>` on the
  command line, an API token per person. Without the setting nothing changes.
  [How](https://trackhop.org/guide/docker#several-people-on-one-server).
- Trackhop Desktop: no changes.

## 0.5.6

- **NetEase Cloud Music and QQ Music as sources**: a public playlist link of 网易云音乐 or QQ音乐 as
  the source of a sync, read-only and with no Chinese account, like SoundCloud's links. Not in the
  Microsoft Store build, which carries official connectors only.
  [How](https://trackhop.org/guide/netease-qq-music).

## 0.5.5

- **On the phone, as an app**: the server's page can be added to the home screen and opens full
  screen, like an app (a PWA: manifest, icons, a service worker that keeps nothing and says so when
  the server cannot be reached). [How](https://trackhop.org/guide/docker#on-your-phone-as-an-app).
- **Fixed**: a radio sync could find its playlist empty when a new song was being saved at the
  same moment, and add nothing that run. Playlist files are now written whole and swapped in.

## 0.5.4

- **The `trackhop` command**: run syncs (`trackhop run --all --wait`), read their results, list
  playlists, add and remove tracks, import playlist files, back up, from a terminal, cron or Task
  Scheduler. It talks to the Trackhop running on the same computer or in the same container with
  nothing to set up (a key Trackhop leaves in its data folder, accepted from this machine only),
  or to a server elsewhere with its API token. Desktop: `trackhop-cli` (`trackhop` with Scoop and
  Chocolatey); Docker: `docker exec trackhop trackhop …`. [The guide](https://trackhop.org/guide/cli).
- **macOS**: Trackhop Desktop for Apple silicon and Intel Macs, as a `.dmg`. Not signed by Apple:
  the first time, *System Settings › Privacy & Security › Open Anyway*. On the Mac its pages open in
  the browser, with its icon in the menu bar.
- **Linux: a Flatpak** among the release's files (`Trackhop-Desktop-v<version>-linux.flatpak`),
  built from source as Flathub would: `flatpak install --user` it. Its data lives in
  `~/.var/app/io.github.xlollx.Trackhop/data/trackhop`, apart from the .deb's and the tar.gz's.

## 0.5.3

- **Microsoft Store**: the package for the Store (the release's `.msix`) is built with the Store's
  identity, "Trackhop Desktop". Inside it, Desktop offers only the official connections, the
  personal servers, Last.fm, ListenBrainz and files (store rules, as for the app on Google Play), and
  starting with the computer is Windows' own switch (Settings › Apps › Startup) instead of the
  tray's. The installer, the ZIP, Linux and Docker are unchanged.

## 0.5.2

- **Finding a radio by name** works again when the station directory's usual servers are down:
  Trackhop asks radio-browser.info's live servers first instead of four fixed ones (two of which no
  longer exist), and when none answers it says so plainly and suggests pasting the stream.

## 0.5.1

- **Shazam syncs**: every song you Shazam, in a playlist of your choice on any service. Shazam keeps
  your Shazams in a "My Shazam Tracks" playlist on Spotify once you connect it in Shazam's settings;
  New Shazam sync (on the home page) finds that playlist on your connected Spotify, or says how to
  turn it on, and syncs it every hour. Its card wears Shazam's mark, and the API's syncs say
  `"shazam": true`.

## 0.5.0

- **Radio syncs**: Trackhop listens to an internet radio on the days and in the hours you choose
  and saves the songs it plays to a playlist on any service. Find the station by name (the
  radio-browser.info directory) or paste its stream; the songs are read from the titles the
  station sends (Icecast/Shoutcast), the audio is never kept; announcements, adverts and news are
  left out. Its card wears a radio and shows what is on air. On the home page: New radio sync.

## 0.4.3

- **Playlists and tracks through the API**: a connected service's playlists and their tracks;
  create, rename and delete playlists; add tracks by title and artist, by a link from any service
  or by id (searched and matched as a sync does, already-present ones skipped); remove tracks by id
  or by title and artist.

## 0.4.2

- **More API calls**: Prometheus metrics (`/api/v1/metrics`, for Grafana), turn a sync on or off,
  start a full backup and follow it, download the set-up as a .trackhop file, push a playlist file
  in the body. The webhook also announces finished backups (`backup.finished`).

## 0.4.1

- **Trackhop Desktop is now a beta** too, like the server on Docker. The unofficial connectors stay an
  alpha.

## 0.4.0

- **The server on Docker is now a beta**: it has run scheduled syncs for days without trouble.
  Trackhop Desktop, newer, stays an alpha; the unofficial connectors too.
- **Backup & restore** in one place (Tools): your whole set-up (syncs, services added, matches you
  chose, ignored tracks, settings, File playlists; never sign-ins) saved to a .trackhop file and
  restored here or in the app (1.3.9) or the other Trackhop; the playlists backed up now or on a
  schedule; the backup folder also gets the set-up of the day.
- **Spotify and Deezer without a key** (unofficial, alpha): through their web players, signed in
  with the cookies of a browser where you are signed in, as YouTube Music already was.

## 0.3.0

- **Trackhop Desktop has its own window**: the pages in a window of their own with Trackhop's icon,
  on the system's web engine (Edge's WebView2 on Windows, WebKitGTK on Linux), no browser tab.
  Closing it leaves Trackhop in the tray, where scheduled syncs keep running; "Open in the browser"
  is in the tray's menu. Links to other sites open in your browser.
- **Playlists** tab: each service's playlists and their tracks; rename, delete, take tracks out; or
  tick several and copy them to another service at once (optionally kept in sync daily).
- **API and webhook** (Tools › API): a token for Home Assistant, n8n or scripts to list the syncs,
  start them and read their runs, and a webhook (HMAC-signed) called when a run ends.
- **Back** at the top of every inner page.
- **Report an issue on GitHub** from the Log page, with the version and the last lines of the log
  filled in.

## 0.2.0

The app's Tools on the page.

- **Full backup**: every playlist and the library of a service (or all of them) as dated File
  playlists, the last 5 versions kept; by hand, or every day or week; optionally copied to a folder.
- **Duplicates**: the tracks a playlist has more than once, and the extra copies removed.
- **Unplayable tracks**: the entries a service no longer plays, replaced by a playable version or
  removed, with an undo.
- **AI playlist**: your own AI (free keys from OpenRouter, Groq or Gemini, or a local model with
  Ollama, no key at all) writes a playlist from a description; Trackhop creates it on a service or
  in File.
- **Your numbers**: what the syncs did, as the app's Stats (Tools, or from the home page).
- **Buy me a coffee** in the footer: optional, nothing is unlocked.

## 0.1.0

The first release with its own version, and the first of Trackhop Desktop (alpha).

- **Trackhop Desktop** for Windows (MSI, no administrator needed, or a portable ZIP) and Linux (.deb
  or tar.gz): the engine and its web page as a program, with its own Java runtime, an icon next to
  the clock (open, start with the computer, quit) and nothing reachable from other computers.
- **The app's look**: its colours, cards and pill buttons, the services' marks, the brand beam, the
  syncs' colours (picked in a sync's settings), and **Sync all now** on the home page.
- **Review after a run**, as in the app: tracks not found (take the closest candidate, search by hand
  and pick, or ignore), uncertain matches (keep, or replace), tracks a mirror sync removed (put back).
- **File playlists**: upload a playlist file in any format Trackhop reads; download each one as CSV,
  M3U, XSPF, JSPF or plain text; a sync that writes to File has a **Download CSV** button.
- **Tools › Export a playlist**: any connected service's playlist to those formats, or into File.
- A sync can be renamed.
