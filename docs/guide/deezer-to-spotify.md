---
title: Transfer Deezer playlists to Spotify
description: Move Deezer playlists and favourite tracks to Spotify for free, and keep them in sync. Open source, on Android, Windows, macOS, Linux and Docker.
---

# Transfer Deezer playlists to Spotify

Trackhop copies your Deezer playlists and favourite tracks to Spotify and can **keep them in sync**.
Free and open source, no Trackhop account, no limit on the number of tracks.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer (also on
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/)), or [Trackhop Desktop](desktop) /
  the [Docker server](docker).
- Your Deezer account and a Spotify account (free or Premium).

## Step by step

1. **Connect Deezer** (*Accounts → Add connector → Deezer*) and sign in on Deezer's page.
2. **Connect Spotify**.
3. *Syncs → New sync*: source *Deezer* and a playlist (or *Favourite tracks*), target *Spotify* and
   *create a new playlist*. For many playlists at once: *Tools → Transfer many playlists*.
4. **Preview**, run, and check the **review** for the tracks that need a choice.

## Good to know

- Through Deezer's official API, playlists carry no ISRC code, so tracks are matched by title,
  artist, album and duration; the quick sign-in reads the ISRC too. Doubtful matches go to the
  review, never added by guesswork.
- A public Deezer playlist can be synced from its link alone, without signing in to Deezer.
- The same works towards YouTube Music, Apple Music, TIDAL, Amazon Music, Navidrome, Jellyfin and
  Plex.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
