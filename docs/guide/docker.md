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

The image is `ghcr.io/xlollx/trackhop:latest`, or a version (`:1.3.0`): Docker and Trackhop Desktop
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
| `SONGPORT_PASSWORD` | none | Asks for this password on every page. The browser also asks for a user name: any will do, unless `SONGPORT_USER` sets one. **Set it** whenever anyone else can reach the port. With `SONGPORT_MULTIUSER`, the admin's password. |
| `SONGPORT_USER` | none | The user name that goes with `SONGPORT_PASSWORD`: set, only this name signs in (otherwise any will do). With `SONGPORT_MULTIUSER`, the admin's name instead of `admin`. |
| `SONGPORT_MULTIUSER` | off | `true`: [several people](#several-people-on-one-server), each with their own services and syncs, signing in with a user name and a password. Needs `SONGPORT_PASSWORD`. |
| `SONGPORT_LOCALE` | English | Language of the texts that come from the engine: `it`, `de`, `fr`, `es`, `pt-BR`, `pl`, `ru`, `tr`, `zh`, `ja`, `ko`. |
| `SONGPORT_PORT` | `8080` | Port inside the container. |
| `SONGPORT_DATA` | `/data` | Where everything is kept (the volume). |
| `SPOTIFY_CLIENT_ID`, `TIDAL_CLIENT_ID`, `DEEZER_APP_ID`, `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY` | none | Your keys, if you'd rather pass them here than type them in the page. |
| `TICKETMASTER_API_KEY` | none | A Ticketmaster Consumer Key (never its secret) for Tools › Concerts, for everyone on this server; each person can also save their own in Settings. Without one, MusicBrainz's dates only. |
| `SETLISTFM_API_KEY` | none | A setlist.fm API key for Tools › Setlists and setlist.fm links, for everyone on this server; each person can also save their own in Settings. |
| `LASTFM_SHARED_SECRET` | none | The "Shared secret" of your Last.fm API key: needed only to love tracks on Last.fm. |
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
| Last.fm | yes, as a source; Loved tracks also as a target | Your user name; to love tracks, your password once (only the session Last.fm gives back is kept) and `LASTFM_SHARED_SECRET` |
| ListenBrainz | yes, as a source | Your user name |
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

### More than one account or server

A service can be on the list more than once: two Jellyfin servers (yours and your parents'), two
Spotify accounts, Navidrome at home and at the office. In **Services**, pick
*Jellyfin: Add another account* in **Add a service** (or **Add another account** at the foot of the
service's page), give it a name if you like ("Jellyfin di casa"), then sign in to it as to the
first. Each account keeps its own sign-in; the name can be changed later on its page.

New syncs offer every account on both sides, so a playlist can go from one Jellyfin server to the
other. Only a playlist onto itself is refused. An extra account that a sync uses cannot be removed:
delete or change those syncs first. The main account is removed as before.

Extra accounts work for the official connections (Spotify, YouTube, TIDAL, Deezer with your own
key, which every account of the service shares), the personal servers (Navidrome / Subsonic,
Jellyfin, Emby, Plex), Last.fm and ListenBrainz, and for the connectors signed in with pasted
cookies (YouTube Music, Spotify and Deezer web, LiveOne, Anghami, Qobuz): each account pastes its
own cookies, from a browser signed in to that account, and keeps its own session apart from the
others.

The extra accounts and their names are part of the set-up: *Tools › Backup & restore* and
`GET /api/v1/config` carry them, without the sign-ins, as for the main ones. In
`GET /api/v1/services` an extra account's `id` is `jellyfin@2`, with `serviceId` and `slot`; that id
is the one to use in the other calls. A data folder from an older version reads as it did.
With [several people](#several-people-on-one-server), each person's accounts are their own.

### Jellyfin Quick Connect

Jellyfin behind single sign-on, or a user without a Jellyfin password: on the Jellyfin service's
page, under the sign-in form, enter the server's address and choose **Sign in with Quick Connect**.
Trackhop shows a code; on a device where you are already signed in to Jellyfin, open your profile ›
*Quick Connect* and enter it. The page notices the approval within a few seconds and signs in. If
Quick Connect is off on the server, the page says so (an administrator turns it on in
*Dashboard › General*). Emby has no Quick Connect.

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
6. If your playlists live on a **brand account** (a channel of its own under the same Google
   account), pick it under **Channel** on the same page once connected: the list shows the
   account's own channel and its brand accounts by name. Without a choice the account's own channel
   is used, as before.

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
   `https://www.liveone.com`, and copy the whole value of **`slackerUser`** (it holds the refresh token).
3. In Trackhop, **Services › Add › LiveOne (web)**, paste it and press **Connect**.

Trackhop renews the session from the refresh token in it as the player does; it lasts until you sign out of
LiveOne in that browser. It is as good as your password: keep it to yourself.

What works: a free account's **presets**, shown as the liked songs, as a source and as a target
(songs are added, not removed); search. Not yet: playlists of your own (they need LiveOne's paid
plan), removing songs, and stations as a radio source (LiveOne plays them only in the US).

## Anghami (unofficial)

Anghami has no public API: Trackhop goes through play.anghami.com's own web player, unofficial and
alpha, like the web connectors above. No developer key. (Public playlist links work without any of
this: paste them as the source of a sync.)

1. In a desktop browser, sign in to [play.anghami.com](https://play.anghami.com).
2. Open the developer tools (F12), then **Network**; type `gateway` in the filter and open your library.
3. Right-click one of the `gateway.php` requests › **Copy** › **Copy URL**.
4. In Trackhop, **Services › Add › Anghami (web)**, paste it and press **Connect**.

The `sid` in that address is the session: it lasts until you sign out of Anghami in that browser, and
it is as good as your password, so keep it to yourself.

What works: your playlists and your liked songs, as a source and as a target (add, remove, create,
rename, delete); search.

## Qobuz (unofficial)

Qobuz gives its API only to approved partners: Trackhop goes through play.qobuz.com's own web
player, unofficial and alpha, like the web connectors above. No developer key.

1. In a desktop browser, sign in to [play.qobuz.com](https://play.qobuz.com).
2. Open the developer tools (F12), then **Network**; type `api.json` in the filter and open your playlists.
3. Right-click one of the requests › **Copy** › **Copy as cURL** (or copy its request headers).
4. In Trackhop, **Services › Add › Qobuz (web)**, paste it and press **Connect**.

What Trackhop keeps is the `X-User-Auth-Token` of that request: it lasts until you sign out of Qobuz in
that browser, and it is as good as your password, so keep it to yourself.

What works: your playlists and your favourite tracks, as a source and as a target (add, remove,
create, rename, public or private, delete); search, by ISRC first.

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

1. Open the page and sign in as **admin** (or the name in `SONGPORT_USER`, if you set one), with
   `SONGPORT_PASSWORD`.
2. In **Users**, add each person with a user name and what they may use (below), then **Create an
   invitation link** and send it to them (a message, an email…).
3. They open the link, choose their own password and are in: they connect their own services. From
   then on they sign in at the same address with their name and password. The 👤 link with their
   name, in the top bar, is where they change their password or sign out.

- **Invitation links** work once, for 7 days, and are shown only when made. Lost or expired? In
  **Users**, *New link* makes another (the old one stops working); *Cancel invitation* ends it. The
  link starts with `SONGPORT_URL`, so set that to the address the others reach. Rather choose the
  password yourself? Under *Or choose their password yourself*, then tell them name and password.

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

## On Azure (beta)

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fxlollx%2FTrackhop%2Fmain%2Fpackaging%2Fazure%2Fazuredeploy.json)

No server at home? The button creates Trackhop on **Azure Container Apps** in your own subscription:
a small app that is always on, with an HTTPS address, and its data on an Azure Files share. You choose a
name, a region, a user name and a password; the address is shown at the end of the deployment.
It is billed to your Azure subscription: about $5 a month in most regions when it sits idle between syncs, up to about $15 if it is busy all the time (estimate from Azure's list prices for Container Apps after its monthly free grant; storage costs cents) (check the
[pricing calculator](https://azure.microsoft.com/pricing/calculator/) for your region); to remove it, delete the resource group.
From a datacenter, use the official connectors: the unofficial ones are blocked more often.
Details: [packaging/azure](https://github.com/xlollx/Trackhop/tree/main/packaging/azure).

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
- **Two-way**: tick it in a new sync's form or on a sync's page, between two existing playlists of
  yours, and Trackhop also makes the reverse sync (marked ↔), so what you add on either side reaches
  the other. The ↔ sync follows the first one's timing, colour, label, tags and star. With *removals
  too*, a track you remove on one side is removed on the other. Untick it and the ↔ sync is deleted;
  the playlists stay as they are. *Turn off* and *Delete* on either of the two act on both.
- **Removals held back**: a run that would remove a quarter or more of the destination (or of what
  a two-way pair had in common) keeps those tracks, because a playlist the service listed only in
  part looks the same. The sync then says *N removals held back*: open it to see them, tick *Remove
  these N tracks* and run. The confirmation serves one run started by hand, within 12 hours; a
  scheduled run never removes them.
- **Many syncs at once**: when four or more syncs read or write the same service (Spotify, YouTube
  Music, the unofficial web connectors…) within the same half hour, the service can take it for
  unusual traffic and turn some away. The home page then shows a card, as the app does: *Got it*
  hides it until the group changes, *Show them* lists those syncs, *Spread them* shows new times
  (each sync a little after the one before; days and frequency stay) and saves them only after
  *Confirm*. The same from the [API](#api) (`/api/v1/syncs/crowded`, `/api/v1/syncs/spread`) and
  the [command line](cli) (`trackhop crowded`, `trackhop spread`).
- The log is in the page, in `docker logs trackhop` (or the name you gave the container), and in
  `songport.log`.
- **Asking for help**: `GET /api/v1/diagnostics` (or `trackhop diagnostics`) gives the same report
  as the app's Log page: version, system, services, syncs, the last runs and the log since a time
  (`?since=24h`), without tokens, cookies, e-mail addresses, your servers' addresses or your user
  name in folder paths; the names of syncs, playlists and tracks only with `?names=true`.
  `POST /api/v1/report` (or `trackhop report`) makes a GitHub issue ready from it and gives you the
  link that opens it prefilled: nothing is sent, you read it and send it. If GitHub already has an
  issue for the same error (a signature hidden in the text, looked up without an account), it tells
  you to add a comment there, or to update when a newer version fixed it.

## Tools

The page's **Tools** are the app's: Export a playlist, File playlists (upload any format, download
CSV, M3U, XSPF, JSPF or text), **Full backup** (by hand, daily or weekly, optionally copied to a
folder: mount one, for example `-v /mnt/nas/trackhop:/backups`, and enter `/backups`),
**Duplicates**, **Unplayable tracks**, **MusicBrainz tags** (the songs on your Navidrome, Jellyfin, Emby or Plex server without them), **[Missing on my server](self-hosted-playlist-sync#missing-on-my-server)** (the tracks of your playlists your own server lacks, to tick and download), **AI playlists** and, on Docker only, **[Lyrics](#lyrics)**. Long runs go on in the background: their
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

### No key needed

The first form on **Tools › AI playlist** needs no AI and no key, and it is the same on every build
(Android, Docker, Desktop). Type one to five artists you like, or pick a playlist of yours (its most
frequent artists are the starting point, its own tracks are left out), a length (25, 50, 100) and
how adventurous: *Familiar* keeps to the closest artists and their best-known songs, *Discovery*
skips the obvious names and the biggest hits. Trackhop then asks open music data:

- [MusicBrainz](https://musicbrainz.org) for who the artists are (one request a second, as it asks);
- [ListenBrainz](https://listenbrainz.org) for the artists their listeners play alongside them, and for
  each artist's most played recordings;
- Deezer's public catalogue for an artist ListenBrainz will not tell about: since 2026 ListenBrainz
  answers anonymous requests only from its cache. Connect ListenBrainz with your token (Services)
  and ListenBrainz answers for every artist.

It mixes them, a few songs per artist and never the same song twice, searches them on the service
you chose like any sync, and creates the playlist (or saves it into File). Building a list takes
from a few seconds to a minute or two for 100 tracks; the page shows how far it is. In the app
it is **Tools › Playlist generator › No key needed**, the mode the card opens in while no AI is set
up, with the list shown for a look before anything is created.

### With your AI

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

## Notifications

A short message on your phone or in a chat when a sync ends with something to know: in
**Settings › Notifications**, add as many channels as you like, each with its own events (a sync
added or removed tracks, a sync failed or a sign-in expired, tracks to review, removals waiting for
your confirmation, a weekly [New releases](new-releases) sync found new music of your artists), and
try it with **Send test**. The Android app has the same channels, in *Settings › Notification
channels*: there their keys are kept encrypted on the phone and never exported either. The message says the sync's name, `+added
−removed` and the error's first line: never your tokens or your server's address. Sending happens
apart from the sync (10 seconds at most per channel); a channel that fails is noted in the log and
never fails the sync. Tokens are kept in the data folder like the sign-ins, shown masked
(`••••abcd`), and left out of the `.trackhop` set-up file: after restoring it on another server,
enter them again (the channels wait, turned off). On a server for several people each has their own.

- **Telegram**: make a bot with [@BotFather](https://t.me/BotFather) (`/newbot`) and paste its token;
  send your bot a message, then enter your chat id (@userinfobot tells it; a channel's `@name` works
  once the bot is an admin there).
- **ntfy**: subscribe to a topic in the ntfy app and enter it; the server is `https://ntfy.sh` or your
  own. On ntfy.sh anyone who knows a topic can read it: pick one hard to guess, or use an access
  token for a protected topic.
- **Gotify**: in Gotify, *Apps › Create application*; enter the server's address and the app's token.
- **Discord**: the channel's *Settings › Integrations › Webhooks › New webhook › Copy webhook URL*.
- **Slack**: an app with an [incoming webhook](https://api.slack.com/messaging/webhooks) for a
  channel; paste its URL.
- **Matrix**: an account (a bot's, best) that is in the room: the homeserver, its access token
  (Element: *Settings › Help & About › Access token*) and the room's id (`!…:server`, in *Room
  settings › Advanced*).
- **Pushover**: your user key (pushover.net's front page) and the API token of an application you
  create there.

From a terminal, `trackhop notify` lists the channels and `trackhop notify test <name or id>` sends a
test. For the whole run as JSON (Home Assistant, n8n), use the [webhook](#api) instead.

## API

Other tools (Home Assistant, n8n, Node-RED, a script) can do what the page does: make and change
syncs, run them and review what they did, read and edit playlists, bring in files, set up
notifications. Turn it on in **Tools › API**: make a token (shown once; only its hash is kept) and send
it as `Authorization: Bearer <token>`. It is separate from the page's password; on Trackhop Desktop
only the computer itself can reach it. From a terminal or a script, the [`trackhop` command](cli)
does the same with less typing: `docker exec trackhop trackhop run --all`, no token needed inside
the container. The same token lets an AI assistant use Trackhop at `/mcp` (Model Context Protocol): [how](cli#with-an-ai-assistant-mcp).

Every call is also described in `GET /api/v1/openapi.json` (OpenAPI 3, no token needed: it holds
nothing of yours), for the tools that read one, such as Postman or a generated client. Ids in paths
are the ones the lists give: a sync's `id`, a run's `id`, a service's `id` (`spotify`, `ytm`,
`navidrome_1`…), a playlist's `id`. Refusals answer `{"error": "why"}` with a 4xx code.

**General**

| Call | What it does |
|---|---|
| `GET /api/v1/status` | Version, how many syncs, which are running, the last run. |
| `GET /api/v1/metrics` | The syncs as Prometheus metrics (text). |
| `GET /api/v1/stats` | Your numbers: tracks moved, time saved, busiest route, top artists. |
| `GET /api/v1/tasks` | The background tasks (backups, duplicates, lyrics), running or ended in the last hour. |
| `GET /api/v1/tasks/{task}` | One background task: its progress, whether it is done, its error. |
| `GET /api/v1/log` | The server's last log lines (admin only). |
| `GET /api/v1/diagnostics` | A report for asking for help (admin only): version, system, services, syncs, the last runs and the log since a time (`?since=24h`; `90m`, `7d`, `2w`), without secrets; names of syncs, playlists and tracks only with `?names=true`. With the last error and its signature. |
| `POST /api/v1/report` | A GitHub issue made ready, never sent (admin only): `title`, `body`, `newIssueUrl` (prefilled). GitHub is searched for the error's signature first: `suggestion` is `new`, `comment` (an open issue has it: `comment` and `commentUrl`), `update` (fixed in a newer version, `latest`) or `comment_closed`. Body: `{"since"?, "names"?, "title"?, "description"?}`. |

**Syncs**

| Call | What it does |
|---|---|
| `GET /api/v1/syncs` | Every sync with its source, target, timing, label, tags and last result; `?tag=genre:metal`, `?label=` and `?favorite=true` narrow the list. |
| `POST /api/v1/syncs` | Make a sync; `"twoWay": true` also makes the reverse sync (marked ↔). |
| `POST /api/v1/syncs/run-all` | Start every sync that is turned on. |
| `POST /api/v1/syncs/run` | Start a group of syncs: those with a tag (`?tag=genre:metal`), wearing a label (`?label=`) or starred (`?favorite=true`), turned on. |
| `GET /api/v1/tags` | The tag keys the syncs use, each with its values. |
| `GET /api/v1/labels` | The labels: the ready colours and your own, each with its name and how many syncs wear it. |
| `POST /api/v1/labels` | Name a ready colour, or save a colour of your own as a label. |
| `PATCH /api/v1/labels/{label}` | Rename a label or give it another colour: its syncs follow. |
| `DELETE /api/v1/labels/{label}` | Delete a label: its syncs keep their colour, without the name. |
| `GET /api/v1/syncs/{sync}` | One sync and its last runs. |
| `PATCH /api/v1/syncs/{sync}` | Change a sync: any of name, enabled, schedule, every, at, days, changes, follows, color, label, tags, favorite, twoWay, twoWayRemovals. |
| `DELETE /api/v1/syncs/{sync}` | Delete a sync (the playlists stay as they are); the other half of a two-way pair goes with it. |
| `POST /api/v1/syncs/{sync}/run` | Start a sync now; anyway=true after a run held back by the target's size check; `{"confirmRemovals": true}` lets this run make the removals a run holds back. |
| `POST /api/v1/syncs/{sync}/stop` | Stop a running sync. |
| `POST /api/v1/syncs/{sync}/pause` | Pause a running sync. |
| `POST /api/v1/syncs/{sync}/resume` | Resume a paused sync. |
| `POST /api/v1/syncs/{sync}/enable` | Turn a sync's schedule on (both halves of a two-way pair). |
| `POST /api/v1/syncs/{sync}/disable` | Turn a sync's schedule off (both halves of a two-way pair). |
| `POST /api/v1/syncs/{sync}/move` | Move a sync in the list: a position from 1, or up, down, top, bottom. |
| `GET /api/v1/syncs/{sync}/preview` | What a run would do now, without changing anything. |
| `GET /api/v1/syncs/{sync}/decisions` | The review's choices between the sync's two services (ignored, matched by hand, kept), each with when it was made. |
| `POST /api/v1/syncs/{sync}/decisions` | Bring in review choices made elsewhere: `{"decisions": [{"source": "<id>", "kind": "ignore" \| "match" \| "keep", "track": "<id>", "at": 1731234567890}]}`; the newest choice of each track wins, and the answer lists them all. |
| `GET /api/v1/syncs/crowded` | Whether many syncs reach one service at about the same time: the service, the time, the syncs, `dismissed` (the page's *Got it*) and `canSpread`. |
| `POST /api/v1/syncs/spread` | New times for those syncs (`changes`: `from`, `to`): only shown, saved with `?apply=true` (or `{"apply": true}`). |

**Runs**

| Call | What it does |
|---|---|
| `GET /api/v1/syncs/{sync}/runs` | A sync's runs, newest first. |
| `GET /api/v1/runs` | Every run, newest first; sync= for one sync's. |
| `GET /api/v1/runs/{run}` | One run with its tracks (added, removed, not found, to check) and its notes. |
| `GET /api/v1/runs/{run}/review` | A run's review: not found, to check, removed. |
| `POST /api/v1/runs/{run}/review` | Review a run: ignore, keep, discard, pick, replace or restore. |

**Services**

| Call | What it does |
|---|---|
| `GET /api/v1/services` | The services added, and whether each is connected; each account apart (`id` `jellyfin@2`, with `serviceId` and `slot`). |
| `GET /api/v1/services/{service}` | One service: connected, account, and what it can do. |
| `GET /api/v1/services/{service}/search` | Look for a track: q is "Artist - Title" or a link. |
| `GET /api/v1/convert` | A track on another service: track is "Artist - Title" or a link, to the service's id. |
| `POST /api/v1/convert` | Several tracks on another service. |
| `GET /api/v1/wanted` | Missing on my server: the tracks your syncs to your own server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy) could not find, each once, with artist, title, album, length, ISRC, MusicBrainz id (when your syncs' lookups found it), the syncs that miss it and when it was first seen. `?format=json` (default), `csv`, `m3u`, `text` or `lidarr`; `group=album`, `all=true` (with the ticked ones), `sync=`, `service=`, `since=7d` (or `24h`, `2026-11-01`). |
| `GET /api/v1/releases` | New releases: the albums, EPs and singles the artists of a playlist released lately (Deezer and MusicBrainz, no account). `?service=spotify&playlist=<id>` (or `playlist=spotify:<id>`), `days=30` (1 to 365), `upcoming=true` adds what is announced, `artists=50` (up to 100), `tracks=true` adds the songs. Each artist is cached a day; a first search takes about a second per artist. The answer's `source` is the source of a weekly "New releases" sync: `POST /api/v1/syncs` with it, `"schedule": "weekly"`, `"changes": "archive"`. |
| `POST /api/v1/wanted/got` | Tick entries as got (`{"ids": ["…"]}`; `"got": false` puts them back). |
| `GET /api/v1/concerts` | Concerts of a playlist's artists: `?service=spotify&playlist=<id or name>&country=IT`. The upcoming dates (date, time, city, venue, country, link, source) of the artists the playlist holds most, from Ticketmaster (your key in Settings, or `TICKETMASTER_API_KEY`) and MusicBrainz, a date both know once; artists without dates get search links; `notes` (the day's Ticketmaster limit reached, coverage) and `attribution` to show with them. Asked only on this call; answers kept a day per artist and country. |
| `GET /api/v1/setlists` | Setlists of a playlist's artists: `?service=spotify&playlist=<id or name>`. The latest setlist.fm setlists (up to 5 each) of the artists the playlist holds most: date, venue, city, country, tour, `url` (its setlist.fm page), `songs` (title, `coverOf`, `tape`, `encore`) and `tracks` ("Artist - Title", tapes left out, ready for a playlist). Needs a setlist.fm key (Settings, or `SETLISTFM_API_KEY`); `notes` (the day's limit reached…) and `attribution` to show with them. Asked only on this call; answers kept a week per artist. |
| `GET /api/v1/records` | Records and merch of a playlist's artists: `?service=spotify&playlist=<id or name>&currency=EUR`. For each artist their CDs, vinyl and cassettes on Discogs (title, year, formats, link; with your Discogs token in Settings, `forSale`, `lowestPrice`, `currency`, `sellUrl`), their official site, shop, Bandcamp and social pages from MusicBrainz, and shop searches. Plain links; `records` is `null` when Discogs was not asked (`slowedDown`); show `attribution` with them. |
| `GET /api/v1/tools/mbtags` | The songs on a Navidrome, Jellyfin, Emby or Plex server without MusicBrainz tags (`?service=subsonic`; not needed when only one is connected). Read only; a large library takes a while. |

**Playlists**

| Call | What it does |
|---|---|
| `GET /api/v1/services/{service}/playlists` | A service's playlists and library entries. |
| `POST /api/v1/services/{service}/playlists` | Make a playlist. |
| `PATCH /api/v1/services/{service}/playlists/{playlist}` | Rename a playlist. |
| `DELETE /api/v1/services/{service}/playlists/{playlist}` | Delete a playlist. |
| `GET /api/v1/services/{service}/playlists/{playlist}/tracks` | A playlist's tracks. |
| `POST /api/v1/services/{service}/playlists/{playlist}/tracks` | Adds tracks, searched and matched as a sync does: `{"tracks": [{"title": "…", "artist": "…"}, {"link": "https://open.spotify.com/track/…"}, {"id": "…"}, "Artist - Title"]}`. Tracks already there are skipped (`"allowDuplicates": true` to add them anyway); the answer lists what was added, already there and not found. |
| `DELETE /api/v1/services/{service}/playlists/{playlist}/tracks` | Remove tracks. |
| `POST /api/v1/services/{service}/playlists/{playlist}/tracks/remove` | Remove tracks (for tools that cannot send a body with DELETE). |
| `GET /api/v1/services/{service}/playlists/{playlist}/duplicates` | The tracks a playlist holds more than once. |
| `POST /api/v1/services/{service}/playlists/{playlist}/duplicates/remove` | Remove the extra copies (a background task). |
| `GET /api/v1/services/{service}/playlists/{playlist}/export` | A playlist as a file: csv, m3u, xspf, jspf or txt. |

**Files**

| Call | What it does |
|---|---|
| `GET /api/v1/files` | The File playlists. |
| `GET /api/v1/files/{file}` | A File playlist, in a format: csv, m3u, xspf, jspf or txt. |
| `PUT /api/v1/files/{file}` | Bring in a playlist file (CSV, M3U, PLS, XSPF, JSPF, iTunes XML, JSON, text) as the body. |
| `POST /api/v1/files/{file}` | The same as PUT, for tools that only POST. |
| `DELETE /api/v1/files/{file}` | Delete a File playlist. |
| `POST /api/v1/files/zip` | Several File playlists in one .zip, in a format. |
| `GET /api/v1/files/settings` | How names in imported files are read: the layout and your own patterns. |
| `PUT /api/v1/files/settings` | Set the layout (AUTO, ARTIST_TITLE, ARTIST_ALBUM_TITLE, CUSTOM) and the patterns. |
| `POST /api/v1/files/try` | Try patterns on a line from a file, without saving them. |

**Backups**

| Call | What it does |
|---|---|
| `GET /api/v1/backup` | The full backup: running, last run, schedule. |
| `POST /api/v1/backup` | Back up every playlist now (a background task). |
| `GET /api/v1/config` | The whole set-up as a .trackhop file; history=true with the runs. |
| `POST /api/v1/config` | Bring a .trackhop file back in (the body); dryRun=true only says what it holds. |

**Settings**

| Call | What it does |
|---|---|
| `GET /api/v1/settings` | The settings the page offers. |
| `PATCH /api/v1/settings` | Change settings: theme, backupSchedule, backupFolder, updatesCheck. |

**Notifications**

| Call | What it does |
|---|---|
| `GET /api/v1/webhook` | The webhook: address, whether a secret is set, its events. |
| `PUT /api/v1/webhook` | Set the webhook. |
| `DELETE /api/v1/webhook` | Turn the webhook off. |
| `GET /api/v1/notifications` | The notification channels, their tokens masked. |
| `GET /api/v1/notifications/kinds` | The kinds of channel, their settings and the events. |
| `POST /api/v1/notifications` | Add a channel. |
| `PATCH /api/v1/notifications/{channel}` | Change a channel (a secret left out keeps its value). |
| `DELETE /api/v1/notifications/{channel}` | Remove a channel. |
| `POST /api/v1/notifications/{channel}/test` | Send a test message. |

**Locks**

| Call | What it does |
|---|---|
| `POST /api/v1/locks` | Reserve a playlist before writing to it: `{"target": "spotify\|37i9dQZF1DX…", "holder": "Pixel 8", "ttl": 600}` (seconds, 30 to 3600). 200 with the reservation's `id` and `until`; 409 with who holds it (`holder`) and `until` when. [Coordinate with my server](#coordinate-with-my-server) |
| `PUT /api/v1/locks/{lock}` | Renew a reservation for `ttl` seconds from now; 404 once it ended: reserve it again. |
| `DELETE /api/v1/locks/{lock}` | Give a reservation back. |

**Lyrics**

| Call | What it does |
|---|---|
| `GET /api/v1/lyrics` | Lyrics for the music folder (Docker, admin): state and last run. |
| `POST /api/v1/lyrics` | Looks for lyrics now (202, with a task id): `?folder=Queen/Jazz` for one folder, `&synced=true` for synced lyrics only, `&retry=true` to ask again for the ones not found. [Lyrics](#lyrics) |
`GET /health` answers `ok` with no token: for Docker's health check, Uptime Kuma or a reverse proxy.

```sh
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/syncs
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/syncs/run-all
```

**Making and changing a sync.** The same checks as the page: the playlists must be ones the
services list, a playlist cannot sync onto itself. Without `target.playlist`, a new playlist is
made at the first run. The timing is `schedule` (`manual`, `hourly`, `every_6h`, `daily`,
`weekly`) or `every` in minutes, with `at` (`"07:30"`) and `days` (1 = Monday … 7 = Sunday);
`changes` is `add` (only add), `mirror` (also remove what leaves the source) or `archive` (keep
every track ever seen); `color` a name (`teal`) or `#RRGGBB`. A `PATCH` changes only what it names.

**Two-way.** `"twoWay": true` on `POST /syncs` or on a `PATCH` also makes the reverse sync (marked
↔, `"twin": true` in the list) between two existing playlists of yours; `"twoWayRemovals": true`
lets removals travel too. The ↔ sync follows the first one's timing, colour, label, tags and star.
`"twoWay": false` on the first sync deletes the ↔ one; the playlists stay as they are.

**Removals held back.** A run that would remove a quarter or more of the destination keeps those
tracks and says how many in its result (`heldRemovals`). `GET /syncs/{sync}/preview` lists them
(`toRemove`, with `removalsNeedConfirmation`). To let one run make them, start it with
`{"confirmRemovals": true}` (what the preview shows at that moment), or with the ids you saw,
`{"confirmRemovals": ["<id>", …]}`, so that nothing else goes. Scheduled runs never make them.

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"confirmRemovals": true}' http://127.0.0.1:8080/api/v1/syncs/<sync>/run
```

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"source": {"service": "spotify", "playlist": "37i9dQZEVXcJZyENOWUFo7"}, "target": {"service": "ytm"},
       "name": "Discover Weekly", "schedule": "weekly", "days": [1], "at": "08:00", "changes": "archive"}' \
  http://127.0.0.1:8080/api/v1/syncs
```

**Favourites, labels and tags.** Three ways to group syncs, the same as on the page and in the
app. `favorite` (true or false) stars a sync: starred syncs are listed first. A sync wears one
`label`, which is its card's colour: the eight ready colours are labels already (`"label": "teal"`),
and `POST /api/v1/labels` with `{"color": "teal", "name": "Gym"}` names one, or saves a colour of
your own (`"#12A594"`) as a named label; `label` takes a label's id or its name, and the card takes
its colour. `tags` are pairs of your own, `{"genre": "metal", "mood": "gym", "kids": ""}`: the
value may be empty; in a `PATCH`, `tags` replaces the sync's tags.

`GET /api/v1/syncs?tag=genre:metal` lists the syncs with that tag (`tag=genre` for the key
whatever its value; several split by commas, all wanted; case and accents do not matter),
`?label=Gym` those wearing a label, `?favorite=true` the starred ones. The same three on
`POST /api/v1/syncs/run` start that group, the syncs turned on only, and answer with the ones
started:

```sh
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tags": {"genre": "metal", "mood": "gym"}, "favorite": true}' \
  http://127.0.0.1:8080/api/v1/syncs/$SYNC
curl -X POST -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/api/v1/syncs/run?tag=mood:gym"
```

**Reviewing a run.** `GET /api/v1/runs/{run}/review` lists what a run could not find, the matches
to check and what it removed, each with its source id; `POST` the same address does what the
page's review does: `{"action": "ignore", "tracks": ["<id>"]}` (or `"tracks": "all"`), `keep` and
`discard` for matches to check, `pick` and `replace` with `"choice": "Artist - Title"` (or a link,
or the track's id on the destination), and `restore` to put back what the run removed.

**Webhook.** With an address set in Tools › API (or `PUT /api/v1/webhook`), Trackhop POSTs its
events there as JSON: `sync.finished` (the sync, and the run with its added and not-found tracks)
and `backup.finished` (services, failures) unless you choose otherwise; `sync.started`,
`sync.failed` (a failed run sends both `sync.finished` and `sync.failed`) and `wanted.added` (a run
to your own server found tracks missing there for the first time: `sync`, `count`, `tracks` as
`GET /api/v1/wanted` gives them) when you tick them. With a
secret, `X-Trackhop-Signature: sha256=<hex>` is the HMAC-SHA256 of the body. In Home Assistant, a
*Webhook* trigger with that address starts an automation (a notification when tracks were not
found, say); a `rest_command` that POSTs to `/api/v1/syncs/run-all` starts every sync from one.

**Lidarr.** `GET /api/v1/wanted?format=lidarr` answers in the shape of Lidarr's *Custom List*
import list: a JSON array of `{"MusicBrainzId": "<artist MBID>", "ArtistName": "…"}`, one per artist
of the list whose MusicBrainz id is known (Lidarr reads `MusicBrainzId` only, and adds artists, not
single tracks). Lidarr cannot send a token, so Tools › Missing on my server shows a list URL with
its own `key=`: it opens that call and nothing else, and changes when you make a new token. In
Lidarr: *Settings › Import Lists › + › Custom List*, List URL that address.

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

**Playlists and tracks.** A Home Assistant button that adds the song playing in the kitchen to a
playlist, or a script that collects the songs a radio played:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tracks": ["Daft Punk - Digital Love", {"link": "https://open.spotify.com/track/2VEZx7NWsZ1D0eJ4uv5Fym"}]}' \
  http://127.0.0.1:8080/api/v1/services/ytm/playlists/PLxxxxxxxx/tracks
```

`GET /api/v1/convert?track=Daft%20Punk%20-%20Digital%20Love&to=tidal` finds a track on another
service as a sync would, with how sure the match is.

**Scripts.** Push a playlist file and sync it, save the set-up every night, bring it back elsewhere:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary @mix.m3u http://127.0.0.1:8080/api/v1/files/mix.m3u
curl -H "Authorization: Bearer $TOKEN" -o trackhop-$(date +%F).trackhop http://127.0.0.1:8080/api/v1/config
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary @trackhop.trackhop "http://127.0.0.1:8080/api/v1/config?dryRun=true"
```

## Coordinate with my server

The same sync on a phone and on this server would add what is missing twice when both run at
once. In the app, **Settings › Advanced › Coordinate with my server** takes this server's address
(`http://192.168.1.10:8080`, or the address of your reverse proxy) and an API token made in
**Tools › API**. Then, before a run writes to a playlist, the phone reserves it here for ten minutes
and renews the reservation every few minutes while it runs; the reservation is given back at the end,
and ends by itself if the phone dies on the way.

- The playlist is reserved by the other side (this server is writing there, or the phone is): the run
  is skipped, with the note "Running on Pixel 8: skipped" (or "Running on Trackhop server: skipped" on
  the phone). No error and no notification; the next scheduled run tries again.
- The server cannot be reached, or refuses the token: the phone syncs as without the option and its
  log says so. Syncs never wait for the server.
- A two-way pair reserves both playlists. File playlists are each device's own and are not reserved.
- On a shared server, each person's reservations are their own, like their syncs.

Trackhop Desktop answers only the computer it runs on, so a phone can coordinate with a Docker
server; Desktop's own syncs still respect reservations made through its API by scripts on that
computer. Any tool can use the same calls ([Locks](#api)): reserve, renew while writing, give back.

## Not there yet

The connectors that run a service's own web player (Amazon Music, Spotify and Apple Music without a
key, Deezer web, SoundCloud with an account) need a browser inside Trackhop: they are the next step.
The compatibility card and the playlist poster are not on the page yet either.

[Back to the guide](./)
