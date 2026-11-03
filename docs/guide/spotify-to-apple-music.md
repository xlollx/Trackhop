---
title: Transfer Spotify playlists to Apple Music
description: Move Spotify playlists and liked songs to Apple Music for free, and keep them in sync, with an open-source Android app. No account, no limits, no Mac needed.
---

# Transfer Spotify playlists to Apple Music

Moving to Apple Music, or using both? Trackhop copies your Spotify playlists and liked songs to Apple
Music and, if you like, **keeps them in sync** afterwards. It runs on an Android phone: free, open
source, no Trackhop account and no limit on the number of tracks.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer (also on
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/)).
- An Apple Music subscription and a Spotify account (free or Premium).

## Step by step

1. **Connect Spotify** (*Accounts → Add connector → Spotify*).
2. **Connect Apple Music**: sign in with your Apple ID on Apple's own page, inside the app.
3. **One playlist:** *Syncs → New sync*, source *Spotify* and the playlist (or *Liked songs*),
   target *Apple Music* and *create a new playlist*. **Many at once:** *Tools → Transfer many
   playlists*.
4. **Check the preview**, run it, and look at the **review** for the few tracks that need a choice.

## Good to know

- Tracks are matched by the recording's ISRC code where Apple Music has it, then by title, artist
  and duration: nothing is added by guesswork, and what was not found stays in the review with
  suggestions.
- Apple Music lets apps add to a playlist but not remove from it: a sync towards Apple Music adds
  new songs, and says so in its report when a song left the Spotify playlist.
- Apple Music is reached from the phone only: [Trackhop Desktop](desktop) and [Docker](docker) do
  not sign in to it.

[Getting started](getting-started) · [Apple Music to Spotify](apple-music-to-spotify) · [All guide pages](./)
