---
title: Free playlist sync between Spotify, YouTube Music, Apple Music and more
description: Sync playlists for free between Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer, Navidrome, Jellyfin and Plex. No subscription, no track limit, no account. Open source, on Android, Windows, macOS, Linux and Docker.
---

# Free playlist sync, with no catch

Most playlist sync services are free only up to a point: one playlist at a time, a cap on tracks,
and automatic sync behind a subscription. Trackhop is **free with everything included**: every
sync, every playlist, every track, on a schedule, for as long as you use it. It is open source and
runs on your own phone or computer, so there is no server bill to pay for and nothing to upsell.

## What "free" means here

- **No paid plan.** There is nothing to unlock: scheduled syncs, mirror removals, liked songs,
  big playlists and every service are in the same free app.
- **No track or playlist limit.** Move a whole library at once, or sync dozens of playlists.
- **No account.** You do not sign up for Trackhop; you only connect the music services you use.
- **Your logins stay with you.** They are stored encrypted on your device, never on a Trackhop
  server (there is none).
- **No ads** in the builds from [GitHub](https://github.com/xlollx/Trackhop/releases/latest),
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), [Trackhop Desktop](desktop) and the
  [Docker server](docker).
- **Open source** (AGPL-3.0): anyone can read the code, build it, and check what it does.

## What you can sync

- **Services**: Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer, and your own
  Navidrome/Subsonic, Jellyfin or Plex server.
- **What**: playlists, liked songs and favourites, saved albums.
- **How often**: by hand, hourly, every 6 hours, daily or weekly. New songs are added to the
  target; with **mirror removals** deleted ones go too. A preview comes before the first run, and
  big removals wait for your confirmation.

More on how a sync works: [Keep playlists in sync](sync-playlists).

## Where it runs

| | Good for |
|---|---|
| [Android app](getting-started) | Syncing from your phone, on a schedule |
| [Trackhop Desktop](desktop) (Windows, macOS, Linux) | A computer you use every day; syncs while it is on |
| [Docker server](docker) | A NAS, a home server or a Raspberry Pi: syncs around the clock |

## Start in three steps

1. Install Trackhop ([Android](getting-started), [desktop](desktop) or [Docker](docker)).
2. Connect two services under *Accounts → Add connector*.
3. Pick a source playlist and a target, choose a schedule, and look at the preview before the first run.

## Popular pairs

- [Spotify to YouTube Music](spotify-to-youtube-music) · [YouTube Music to Spotify](youtube-music-to-spotify)
- [Spotify to Apple Music](spotify-to-apple-music) · [Apple Music to Spotify](apple-music-to-spotify)
- [Spotify to TIDAL](spotify-to-tidal) · [TIDAL to Spotify](tidal-to-spotify)
- [Spotify to Deezer](spotify-to-deezer) · [Deezer to Spotify](deezer-to-spotify)
- [Spotify to Amazon Music](spotify-to-amazon-music) · [Amazon Music to Spotify](amazon-music-to-spotify)
- [Spotify to Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin)

Coming from a paid tool? See how Trackhop compares with [Soundiiz](soundiiz-alternative),
[TuneMyMusic](tunemymusic-alternative) and [FreeYourMusic](freeyourmusic-alternative).

[Getting started](getting-started) · [All guide pages](./)
