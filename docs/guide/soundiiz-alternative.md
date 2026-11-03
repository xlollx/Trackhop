---
title: A free, open-source Soundiiz alternative
description: Trackhop moves playlists between Spotify, Apple Music, YouTube Music, TIDAL, Deezer, Navidrome, Jellyfin and Plex like Soundiiz, and keeps them in sync on a schedule, free, with no account and no track limits. Open source.
---

# A free, open-source Soundiiz alternative

[Soundiiz](https://soundiiz.com) is a well-known web service for moving playlists between music
services, and it does that job well. Trackhop does the same move, and is built for what comes
after it: **keeping the playlists identical over time**, on a schedule, with no account, no
subscription and no limit on tracks or playlists. It runs on your own phone or computer, so your
music accounts are never handed to a third party.

## Side by side

| | Trackhop | Soundiiz |
|---|---|---|
| Price | Free, everything included | Free plan with limits; more in paid plans |
| Moving playlists | All at once or one by one, no track limit | Free plan: one playlist at a time, with a cap on the tracks you can select; batch transfers are paid |
| Keeping playlists in sync | Every sync, on a schedule (hourly to weekly), additions and, if you want, removals | One sync on the free plan; more in paid plans |
| Account | None | A Soundiiz account |
| Where it runs | On your Android phone, your computer ([Windows, macOS, Linux](desktop)) or your own server ([Docker](docker)) | On Soundiiz's servers, in the browser |
| Who holds your music logins | Only your device, encrypted | The service |
| Your own music server | Navidrome and other Subsonic servers, Jellyfin, Plex | Not among the services it lists |
| Source code | Open (AGPL-3.0) | Closed |

Soundiiz's column follows its own pricing page as of October 2026; check it there for the current
details.

## When Soundiiz is the better choice

- A **one-off move from a browser**, with nothing to install.
- A service Trackhop does not connect to, such as Qobuz, Beatport or Pandora.

## When Trackhop fits better

- You use **two services for good** (Spotify at home, YouTube Music in the car, a Navidrome
  server for your own files) and want the same playlists everywhere, kept up to date by itself.
- You have **big playlists** or many of them, and do not want to move them in pieces.
- You would rather **not give your music accounts to an online service**.
- You want to **back up** your playlists as files, on a schedule.

## Moving to Trackhop

1. Install [Trackhop](getting-started) on Android, or [Trackhop Desktop](desktop) on a computer.
2. Connect the two services in **Accounts**.
3. **Syncs → New sync**: pick the source playlist and the target, then choose how often it
   should run. For a whole library at once: **Tools → Transfer many playlists**.
4. Check the **review** for the few tracks the target does not have: Trackhop suggests
   replacements and never adds anything at random.

Step-by-step guides: [Spotify to YouTube Music](spotify-to-youtube-music) ·
[Apple Music to Spotify](apple-music-to-spotify) · [Spotify to TIDAL](spotify-to-tidal) ·
[Spotify to Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin).

## Questions

**Is Trackhop really free?** Yes: free software under the AGPL-3.0, with no paid plan and no feature
behind a paywall. The Google Play version shows a small ad; the GitHub and F-Droid versions have none.

**Does it work on iPhone?** There is no iPhone app. On a computer, [Trackhop Desktop](desktop) does
everything the phone app does; on a home server, [Docker](docker) runs your syncs around the clock
and its page can sit on an iPhone's home screen.

**Can I keep using Soundiiz too?** Of course: Trackhop works on the playlists themselves, whatever
moved them there.

[All guide pages](./) · [A TuneMyMusic alternative](tunemymusic-alternative)
