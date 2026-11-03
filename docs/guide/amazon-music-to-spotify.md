---
title: Transfer Amazon Music playlists to Spotify
description: Move Amazon Music playlists to Spotify, or Spotify to Amazon Music, for free, and keep them in sync with an open-source Android app. No account, no limits.
---

# Transfer Amazon Music playlists to Spotify

Amazon Music has no public way for other apps to read your playlists, so most transfer services
either skip it or charge for it. Trackhop signs in to Amazon Music on Amazon's own page and copies
your playlists to Spotify, or the other way round, and can **keep them in sync**. Free and open
source, on Android.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) from GitHub or
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), on Android 8.0 or newer.
- Your Amazon Music account (Prime or Unlimited) and a Spotify account.

## Step by step

1. **Connect Amazon Music** (*Accounts → Add connector → Amazon Music*) and sign in on Amazon's page.
2. **Connect Spotify**.
3. *Syncs → New sync*: source *Amazon Music* and the playlist, target *Spotify* and *create a new
   playlist* (or the reverse). For many playlists at once: *Tools → Transfer many playlists*.
4. **Preview**, run, and check the **review** for anything that needs a choice.

## Good to know

- Amazon Music is reached through its web player's own interface, not an official one: it works
  today, and may need an update when Amazon changes its player.
- It is phone-only: [Trackhop Desktop](desktop) and [Docker](docker) do not sign in to Amazon Music.
- Tracks are matched by ISRC where both sides have it, then by title, artist and duration.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
