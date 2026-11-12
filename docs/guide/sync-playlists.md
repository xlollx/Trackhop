---
title: Keep playlists in sync between Spotify, YouTube Music, Apple Music and more
description: Sync playlists automatically between Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer, Navidrome, Jellyfin and Plex. Free, open source, on a schedule, no account.
---

# Keep playlists in sync, automatically

Copying a playlist once is easy; keeping two copies the same is the tedious part. A Trackhop
**sync** remembers a source playlist and a target, and brings the target up to date by hand or on a
schedule: new songs are added, and with **mirror removals** deleted ones go too.

## What a sync does

- **Source and target**: any of Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer,
  Navidrome/Subsonic, Jellyfin, Emby, Plex, Lyrion, Mopidy; liked songs and saved albums too.
- **Schedule**: every few hours, daily, weekly on the day you pick, or on chosen days. On the phone, on [Trackhop Desktop](desktop)
  (while the computer is on) or on a [Docker server](docker) that is always on. Coming in the next
  update: a sync made on the phone [handed over to your server](phone-and-server).
- **Safe by default**: a preview before the first run; big removals wait for your confirmation; a
  run that could not read everything never removes anything.
- **When the source changes**: only add new songs (the default), **copy the latest version**
  (mirror removals), or **keep every track** (an archive: nothing is ever removed, new songs are
  added at the end). The archive is made for playlists that change by themselves, such as Discover
  Weekly, Release Radar, a daylist or a chart: the editor offers the two choices right under such a
  source, and the Desktop and Docker pages have the same three choices.
- **One service is enough**: source and target can be two playlists of the same account (Discover
  Weekly into a playlist of your own on the same Spotify), and the app suggests ready-made ideas
  when you have only one service. See [With one service](getting-started#with-one-service).
- **Order**: optionally keeps the source's order on the target (not for an archive).
- **Two-way**: one switch adds the reverse sync, so what you add on either side reaches the other;
  with **Removals too**, what you remove on one side goes from the other as well. See
  [two-way playlist sync](two-way-sync).
- **Organised**: star your favourite syncs, give colours a name, add tags, and search and filter by
  them ([how](organise-syncs)). [Notifications](notification-channels) on Telegram, ntfy, Discord and
  more tell you what a run changed.

## Popular pairs

- [Spotify and YouTube Music](spotify-to-youtube-music) · [YouTube Music and Spotify](youtube-music-to-spotify)
- [Spotify and Apple Music](spotify-to-apple-music) · [Apple Music and Spotify](apple-music-to-spotify)
- [Spotify and TIDAL](spotify-to-tidal) · [TIDAL and Spotify](tidal-to-spotify)
- [Apple Music and YouTube Music](apple-music-to-youtube-music)
- [Spotify and your Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin)

Free, with no plan to unlock: see [free playlist sync](free-playlist-sync).

[Getting started](getting-started) · [All guide pages](./)
