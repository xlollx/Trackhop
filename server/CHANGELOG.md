# Trackhop Desktop & Server: changes

Docker (`ghcr.io/xlollx/trackhop`) and Trackhop Desktop share this version (`server/VERSION`) and are
released together as `server-v<version>`. The app keeps its own (`v1.x`), and the engine each
server version runs is the app's of the same commit, shown in the page footer. Up to the app's 1.3.8
the Docker image carried the app's version.

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
  [How](https://xlollx.github.io/Trackhop/guide/docker#several-people-on-one-server).
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
  [How](https://xlollx.github.io/Trackhop/guide/docker#several-people-on-one-server).
- Trackhop Desktop: no changes.

## 0.5.6

- **NetEase Cloud Music and QQ Music as sources**: a public playlist link of 网易云音乐 or QQ音乐 as
  the source of a sync, read-only and with no Chinese account, like SoundCloud's links. Not in the
  Microsoft Store build, which carries official connectors only.
  [How](https://xlollx.github.io/Trackhop/guide/netease-qq-music).

## 0.5.5

- **On the phone, as an app**: the server's page can be added to the home screen and opens full
  screen, like an app (a PWA: manifest, icons, a service worker that keeps nothing and says so when
  the server cannot be reached). [How](https://xlollx.github.io/Trackhop/guide/docker#on-your-phone-as-an-app).
- **Fixed**: a radio sync could find its playlist empty when a new song was being saved at the
  same moment, and add nothing that run. Playlist files are now written whole and swapped in.

## 0.5.4

- **The `trackhop` command**: run syncs (`trackhop run --all --wait`), read their results, list
  playlists, add and remove tracks, import playlist files, back up, from a terminal, cron or Task
  Scheduler. It talks to the Trackhop running on the same computer or in the same container with
  nothing to set up (a key Trackhop leaves in its data folder, accepted from this machine only),
  or to a server elsewhere with its API token. Desktop: `trackhop-cli` (`trackhop` with Scoop and
  Chocolatey); Docker: `docker exec trackhop trackhop …`. [The guide](https://xlollx.github.io/Trackhop/guide/cli).
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
