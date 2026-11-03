---
title: A free, open-source TuneMyMusic alternative
description: Trackhop transfers playlists between Spotify, Apple Music, YouTube Music, TIDAL, Deezer, Navidrome, Jellyfin and Plex like TuneMyMusic, and keeps them in sync on a schedule, free, with no track limit and no account. Open source.
---

# A free, open-source TuneMyMusic alternative

[TuneMyMusic](https://www.tunemymusic.com) is a popular web service for moving playlists from one
music service to another. Trackhop does the same transfer, and goes on afterwards: it **keeps the
playlists in sync** on a schedule, for free, with no account and no track limit. It runs on your
own phone or computer, so your music accounts stay with you.

## Side by side

| | Trackhop | TuneMyMusic |
|---|---|---|
| Price | Free, everything included | Free to try, with a limit on free tracks; unlimited transfers in Premium |
| Keeping playlists in sync | Every sync, on a schedule (hourly to weekly), additions and, if you want, removals | Auto-sync in Premium |
| Account | None | Your music services, through its website |
| Where it runs | On your Android phone, your computer ([Windows, macOS, Linux](desktop)) or your own server ([Docker](docker)) | On TuneMyMusic's servers, in the browser |
| Who holds your music logins | Only your device, encrypted | The service |
| Your own music server | Navidrome and other Subsonic servers, Jellyfin, Plex | Not among the services it lists |
| Source code | Open (AGPL-3.0) | Closed |

TuneMyMusic's column follows its own website as of October 2026; check it there for the current
details.

## When TuneMyMusic is the better choice

- A **quick transfer from a browser**, with nothing to install.
- A service Trackhop does not connect to, such as Yandex Music, Anghami, KKBOX or Pandora.

## When Trackhop fits better

- You **switched services but kept the old one**, or use two at once, and want the same playlists
  on both, updated by themselves.
- You have **large playlists** or a whole library to move, with no limit and no subscription.
- You would rather **not give your music accounts to an online service**.
- You want **Navidrome, Jellyfin or Plex**: your own music, in the same playlists as your
  streaming service.

## Moving to Trackhop

1. Install [Trackhop](getting-started) on Android, or [Trackhop Desktop](desktop) on a computer.
2. Connect the two services in **Accounts**.
3. **Syncs → New sync**: pick the source playlist and the target, then choose how often it
   should run. For many playlists at once: **Tools → Transfer many playlists**.
4. Check the **review** for the few tracks the target does not have: Trackhop suggests
   replacements and never adds anything at random.

Step-by-step guides: [Spotify to YouTube Music](spotify-to-youtube-music) ·
[Apple Music to Spotify](apple-music-to-spotify) · [Spotify to TIDAL](spotify-to-tidal) ·
[Spotify to Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin).

## Questions

**Is Trackhop really free?** Yes: free software under the AGPL-3.0, with no paid plan and no track
limit. The Google Play version shows a small ad; the GitHub and F-Droid versions have none.

**Does it work on iPhone?** There is no iPhone app. On a computer, [Trackhop Desktop](desktop) does
everything the phone app does; on a home server, [Docker](docker) runs your syncs around the clock
and its page can sit on an iPhone's home screen.

**Does it move liked songs and albums too?** Liked songs, saved albums and followed artists can be
the source of a sync, as well as playlists.

[All guide pages](./) · [A Soundiiz alternative](soundiiz-alternative)
