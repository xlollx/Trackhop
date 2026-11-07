---
title: Transfer Apple Music playlists to YouTube Music (and back)
description: Move Apple Music playlists to YouTube Music, or YouTube Music to Apple Music, for free, and keep them in sync. Open-source Android app, no Mac or iPhone needed.
---

# Transfer Apple Music playlists to YouTube Music, and back

Trackhop moves playlists between Apple Music and YouTube Music in either direction and can **keep
them in sync**. It runs on an Android phone: free, open source, no account and no track limit.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) from GitHub or
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), on Android 8.0 or newer.
- An Apple Music subscription and your Google account for YouTube Music.

## Step by step

1. **Connect Apple Music** (*Accounts → Add connector → Apple Music*): sign in with your Apple ID on
   Apple's page.
2. **Connect YouTube Music**: sign in on Google's page.
3. *Syncs → New sync*: source and target either way round, then *create a new playlist*. Many at
   once: *Tools → Transfer many playlists*.
4. **Preview**, run, and check the **review** for the tracks that need a choice.

## Good to know

- Towards Apple Music, a sync removes the songs that left when you signed in with your Apple ID.
  With a developer token, Apple's API lets apps add to a playlist but not remove from it: the sync
  adds new songs and reports the ones that left.
- YouTube Music mixes songs and videos: a video's title is read as "artist - title", and doubtful
  matches go to the review instead of being added.
- Apple Music is reached from the phone only.

[Keep playlists in sync](sync-playlists) · [All guide pages](./)
