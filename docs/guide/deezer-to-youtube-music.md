---
title: Transfer Deezer playlists to YouTube Music
description: Copy Deezer playlists and favourite tracks to YouTube Music, with the song version preferred over music videos, and keep them in sync. Free and open source, no account.
---

# Transfer Deezer playlists to YouTube Music

Trackhop copies your Deezer playlists and favourite tracks to YouTube Music and can **keep them in
sync**. YouTube Music mixes songs and music videos in one catalogue; Trackhop picks the **song**, so
your playlist plays the album version and not a video with a long intro.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer (also on
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/)), [Trackhop Desktop](desktop) or the
  [Docker server](docker).
- Your Deezer account and a Google account with YouTube Music (free or Premium).

## Step by step

1. **Connect Deezer** (*Accounts → Add connector → Deezer*) and sign in on Deezer's page.
2. **Connect YouTube Music**: the quick sign-in uses your Google account and has no daily limit;
   the official route uses your own Google API key, whose daily quota Trackhop shows and spreads
   over several days for a big playlist.
3. *Syncs → New sync*: source *Deezer* and a playlist, or *Liked songs* for your **Favourite
   tracks**; target *YouTube
   Music* and *create a new playlist*.
4. **Preview**, run, and check the **review**.

## Good to know

- YouTube Music shares no ISRC codes with other apps, so songs are matched by title, artists and
  length. A live or remixed version is never taken for the studio one without asking.
- **Liked songs** on YouTube Music are also the likes of your YouTube account: a song liked there
  shows on YouTube too.
- A scheduled sync follows Deezer: new favourites reach YouTube Music at the next run, and with
  **mirror removals** the ones you remove leave too.
- The other way round: [YouTube Music to Spotify](youtube-music-to-spotify) shows the same steps
  from YouTube Music.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
