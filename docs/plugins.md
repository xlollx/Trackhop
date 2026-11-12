# Connector plugins

Trackhop talks to music services through their official APIs. Some services have no open API, or
make the official route heavy for a single user (a developer account, a paid program, a daily quota).
For those, Trackhop defines an **open connector interface**: a separate Android app, a *plugin*, can
hold the login to a service and answer Trackhop's requests for playlists and tracks. Trackhop does not
include, download or install plugins; it discovers one that is already installed and offers its
services in the account picker.

## How a plugin is discovered

A plugin declares an activity that handles the intent action `com.xlollx.songport.action.CONNECTOR`
and carries a `<meta-data>` entry named `com.xlollx.songport.connector.authority` with the authority of
its `ContentProvider`. Trackhop calls that provider with `ContentResolver.call()`; the methods and their
arguments are documented in the reference implementation below. The provider must verify the caller's
package and signing certificate before answering.

## Known plugins

### Trackhop Bridge

[Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge) is the reference plugin, by the same
author, free and open source (GPL-3.0). It is distributed on GitHub only, never on Google Play.
It adds:

- **YouTube Music** without a Google Cloud project and without quota.
- **Amazon Music**, which has no open API at all.
- **Spotify** and **Apple Music** sign-in without creating a developer app or paying for the Apple
  Developer Program (the official routes stay available in Trackhop).
- **Deezer** sign-in (alpha) for people who cannot create a Deezer developer app, now that Deezer no
  longer registers new ones (the official route stays available in Trackhop).
- **SoundCloud** with your account (beta): your sets and liked tracks as sources and destinations,
  and public SoundCloud links.

Read this before installing: the Bridge uses the same web interfaces the services' own web players use,
not official APIs. That is against those services' terms of use, it can stop working whenever a service
changes something, and in the worst case a service could restrict the account you sign in with. The
Bridge shows a notice and asks for your acceptance before any sign-in. Your passwords are typed only on
the services' own pages and are never stored; the Bridge keeps the session cookies, encrypted on the
phone, and hands Trackhop only the data or short-lived tokens it needs.

**The Trackhop APK from GitHub and the F-Droid version already include these connectors**: add the
service from Accounts → + and sign in there, the notice comes first. The separate Bridge app is only
for the Google Play version of Trackhop:
[latest release](https://github.com/xlollx/Trackhop-Bridge/releases/latest).

## Methods

Every method is a `ContentResolver.call(authority, method, arg, extras)`. The answer is a `Bundle`:
`error` (a message to show as is) when the call failed, otherwise the fields below. Listings are
JSON in `json`; playlists are `{id, name, count, owned, description}`; tracks are
`{id, title, artists, album, durationMs, setVideoId, uri, explicit, addedAt, kind, year, available, isrc}`
unless said otherwise. Long listings come in pages: `tracks` methods take `offset` and answer
`json`, `total` and, when more follow, `next`.

Every service has `<service>.status` (`connected`, `account`, `version`: the plugin's versionCode)
and `<service>.disconnect`. Trackhop treats a service as served when its status answers `connected`
at all; a plugin that does not know a service answers `error`.

**Several accounts of one service** (Trackhop 1.15): every call may carry a `slot` extra (`"2"`, `"3"`…)
naming an extra account of the service, which the plugin keeps apart from the first one (its own
session, its own WebView profile); the login activities take the same `slot` extra. Absent or empty,
the call is about the account there has always been, exactly as before. A plugin that keeps accounts
apart says so with `slots` in its status answers (`true` for the services that take a slot, `false`
for the ones that serve one account: Amazon Music, SoundCloud); Trackhop never sends a slot to a
plugin whose status answers carry no `slots`, since it would serve the first account to every slot.

| Namespace | Methods |
|-----------|---------|
| (none) YouTube Music | `status`, `disconnect`, `playlists`, `playlistInfo`, `tracks`, `search`, `stats`, `create`, `add`, `remove`, `rename`, `delete`, `visibility`, `reorder` |
| `amazon.` | `status`, `disconnect`, `playlists`, `tracks`, `search`, `create`, `add`, `remove`, `rename`, `delete`, `visibility` |
| `spotify.` | `status`, `disconnect`, `token`, `playlists`, `version`, `playlistInfo`, `tracks`, `search`, `track`, `create`, `add`, `remove`, `rename`, `delete`, `visibility`, `reorder` |
| `apple.` | `status`, `disconnect`, `tokens` |
| `deezer.` | `status`, `disconnect`, `playlists`, `playlistInfo`, `version`, `tracks`, `search`, `track`, `create`, `add`, `remove`, `rename`, `delete` |
| `soundcloud.` | `status`, `disconnect`, `playlists`, `playlistInfo`, `version`, `tracks`, `search`, `create`, `add`, `remove`, `reorder`, `rename`, `visibility`, `delete` |

### Deezer (`deezer.*`, Trackhop Bridge 1.5.0)

The web player's gateway for the account, Deezer's public catalogue API for search. Ids are Deezer's
track and playlist ids (the same as the official API's). `__liked__` is the favourite tracks.

| Method | `arg` | `extras` | Answer |
|--------|-------|----------|--------|
| `deezer.playlists` | | | `json`: playlists, without the "Loved tracks" one |
| `deezer.playlistInfo` | playlist id | | `json`: one playlist |
| `deezer.version` | playlist id or `__liked__` | | `version`: a checksum that changes with the content |
| `deezer.tracks` | playlist id or `__liked__` | `offset` | `json` (tracks, with `isrc`), `total`, `next` |
| `deezer.search` | query | `isrc` (optional) | `json`: tracks; by ISRC first when given |
| `deezer.track` | track id | | `json`: one track, or `null` |
| `deezer.create` | | `name`, `description` | `json`: the new playlist (private) |
| `deezer.add` / `deezer.remove` | playlist id or `__liked__` | `ids` (track ids) | empty; removal is by track id |
| `deezer.rename` | playlist id | `name` | empty |
| `deezer.delete` | playlist id | | empty |

### SoundCloud (`soundcloud.*`, Trackhop Bridge 1.5.0)

`api-v2.soundcloud.com` with the web app's client id and the account's OAuth token. A playlist id is
a numeric set id, `__liked__` for the liked tracks, or the path of a public page (`user/sets/slug`,
`user/likes`, read-only). Tracks are **SoundCloud's own track objects**, trimmed to `id`, `kind`,
`title`, `user.username`, `publisher_metadata` (`artist`, `album_title`, `isrc`, `explicit`),
`duration`, `full_duration`, `permalink_url`, `policy`, `release_date`, `display_date`,
`created_at`, plus `liked_at` for liked tracks: Trackhop reads them with the same parser as public
SoundCloud links.

| Method | `arg` | `extras` | Answer |
|--------|-------|----------|--------|
| `soundcloud.playlists` | | | `json`: own sets, then liked sets (`owned` false) |
| `soundcloud.playlistInfo` | set id or page path | | `json`: one playlist |
| `soundcloud.version` | set id or page path | | `version`: last modification and size (none for likes) |
| `soundcloud.tracks` | set id, `__liked__` or page path | `offset` | `json` (track objects), `total`, `next` |
| `soundcloud.search` | query | | `json`: track objects |
| `soundcloud.create` | | `name`, `description` | `json`: the new set (private) |
| `soundcloud.add` | set id or `__liked__` | `ids` | empty; appended to the set (500 tracks at most) |
| `soundcloud.remove` | set id or `__liked__` | `ids` | empty; every copy of each track leaves the set |
| `soundcloud.reorder` | set id | `ids`: the set's full track list in the new order | empty; refused when the set changed |
| `soundcloud.rename` | set id | `name` | empty |
| `soundcloud.visibility` | set id | `public` | empty |
| `soundcloud.delete` | set id | | empty |

Sign-in activities are started with the actions `com.xlollx.songport.ytmbridge.DEEZER_LOGIN` and
`com.xlollx.songport.ytmbridge.SOUNDCLOUD_LOGIN` (next to `LOGIN`, `AMAZON_LOGIN`, `SPOTIFY_LOGIN`
and `APPLE_LOGIN`). Before the first Deezer or SoundCloud sign-in the notice is shown again, since
its text now names them.

### Amazon Music saved albums (`amazon.*` with `__albums__`, Trackhop Bridge 1.11.0)

The albums the user "liked" in the web player (Library › Albums), through the methods that already
take a playlist id: `amazon.tracks` with `arg` `__albums__` answers `json`, the albums (`kind`
`album`, ids are Amazon's album ASINs); `amazon.add` with `__albums__` and `ids` saves each album and
answers `duplicates` empty, or `error` when Amazon did not save one; `amazon.remove` with
`__albums__` and `ids` removes each one and answers `kept`, the albums Amazon still names as liked.
`amazon.search` with the extra `kind` `album` finds albums to save. Followed artists are not offered:
no listing of them has been seen in the player's traffic yet. Trackhop asks for saved albums only of
a plugin whose status reports `version` 57 or more; an older one would read `__albums__` as a
playlist id.

### TicketOne pages for Concerts (`ticketone.read`)

For Discover › Concerts in Italy: `arg` is the address of a search or artist page of
www.ticketone.it (nothing else is opened). The plugin opens it in a WebView out of sight, at least
1.5 seconds after the previous one, and answers in `json` what the page shows: `state` (`ready`,
`empty`, `challenge`, `consent`, `waiting` or `error`), `ld` (the texts of its JSON-LD scripts),
`cards` (event links with the text around them) and `artists` (artist links). Trackhop reads the
dates out of it. A plugin that does not know the method answers `error` ("unknown method"), and
Trackhop then shows the TicketOne search button only.

### Vivaticket for Concerts (`vivaticket.read`, Trackhop Bridge 1.11.0)

For Discover › Concerts in Italy, in the store builds (the GitHub and F-Droid builds ask Vivaticket
themselves): `arg` is an address of Vivaticket's gateway, `https://apigatewayb2cstore.vivaticket.com/api/`
followed by `Home/Search/`, `Artist/` or `Event/` (nothing else is asked); `extras` may carry
`userAgent`, Trackhop's own (a browser's is never used: the plugin then names itself as Trackhop).
The plugin makes that one GET, without cookies, sign-in or key, at least a second after the previous
one, follows no redirect, and answers `code` (the HTTP status) and `json` (the body). Trackhop paces
the requests too, reads the dates out of the answers and, on an error status or an answer it no
longer understands, leaves Vivaticket alone for an hour. Trackhop asks it only of a plugin whose
status reports `version` 57 or more.

### setlist.fm browsed without a key (`com.xlollx.songport.ytmbridge.SETLIST_BROWSE`, Trackhop Bridge 1.11.0)

For Discover › Setlists in the store builds (the GitHub and F-Droid builds have their own screen):
an activity Trackhop starts for a result with this action, with the extra `artist` (its search on
setlist.fm opens) or `link` (a setlist.fm concert page). It shows www.setlist.fm in a plain WebView
with its normal user agent; the person browses it and opens a concert. While a concert's page is
shown, a bar offers **Add to a playlist** and **Make a playlist**; the tap reads that page once and
answers `RESULT_OK` with `html` (the page's og:title tag, date box and song labels, as the page has
them), `url` (the page's address) and `existing` (`true` for "Add to a playlist"). Nothing else is
asked of setlist.fm, nothing in the background. Only Trackhop gets the answer: started by another
app, the screen closes at once. Trackhop starts it only when the plugin's status reports `version`
57 or more, and says to update the plugin otherwise.
