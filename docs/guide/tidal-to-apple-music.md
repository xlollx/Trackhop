---
title: Transfer TIDAL playlists to Apple Music
description: Copy TIDAL playlists and favourite tracks to Apple Music with exact ISRC matching, and keep them in sync. On Android, free and open source.
---

# Transfer TIDAL playlists to Apple Music

TIDAL and Apple Music both know the **ISRC** of each recording, the code that names one exact
version of a song. That makes this one of the cleanest moves there is: Trackhop finds nearly
every song exactly, in the same version, and asks only about the few Apple Music does not carry.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer (also on
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/)). Apple Music is reached from the phone
  only: [Trackhop Desktop](desktop) and [Docker](docker) do not sign in to it.
- A TIDAL subscription and a free TIDAL developer key (the app shows each step).
- An Apple Music subscription.

## Step by step

1. **Connect TIDAL** (*Accounts → Add connector → TIDAL*) and create your key on TIDAL's developer
   site, as the app shows.
2. **Connect Apple Music** with your Apple ID, on Apple's page inside the app.
3. *Syncs → New sync*: source *TIDAL* and a playlist, or *Liked songs* for your favourite tracks;
   target *Apple Music* and *create a new playlist*. Saved albums move too: pick them as the source.
4. For many playlists: *Tools → Transfer many playlists*.
5. **Preview**, run, and check the **review**.

## Good to know

- Matching by ISRC keeps the version too: a remaster, a live take or an explicit version on TIDAL
  is looked for as that same recording on Apple Music.
- Signed in with your Apple ID, **mirror removals** also removes on Apple Music what you remove on
  TIDAL; with a developer token Apple lets apps only add.
- To keep both for a while, schedule the sync: new TIDAL favourites reach Apple Music by themselves.
- Leaving TIDAL for Spotify instead: [TIDAL to Spotify](tidal-to-spotify).

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
