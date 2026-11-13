---
title: Sync Spotify playlists to YouTube Music
description: Copy a Spotify playlist to YouTube Music and keep it in sync automatically, free and open source, on your Android phone. No account, no server.
---

# Sync Spotify playlists to YouTube Music

Trackhop copies a Spotify playlist (or your liked songs) to YouTube Music and then **keeps it in
sync**: add a song on Spotify and it shows up on YouTube Music at the next run, by hand or on a
schedule. It is free, open source and runs on your Android phone: no account, no subscription, no
website that sees your playlists.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on an Android phone (8.0 or newer),
  from F-Droid, Obtainium or GitHub. No phone? [Trackhop Desktop](desktop) does it on Windows, macOS
  and Linux too.
- Your Spotify and YouTube Music accounts.

## Step by step

1. **Connect Spotify.** *Accounts → Add connector → Spotify.* The quick route signs you in on
   Spotify's own page, nothing to create; the official route uses a free developer key in your own
   name and the app walks you through it.
2. **Connect YouTube Music** the same way. The quick route has no daily limit; the official route
   (YouTube's API) allows roughly 200 added tracks a day, and a bigger playlist carries on by itself
   the next day.
3. **Create the sync.** *Syncs → New sync*: source *Spotify*, the playlist (or *Liked songs*);
   target *YouTube Music*, *create a new playlist*. Leave the name empty to keep Spotify's.
4. **Open the preview.** It shows what will be added before anything changes.
5. **Run it**, then pick a schedule (every hour, every 6 hours, daily or weekly) to keep the two in
   step.

## Tips

- **Mirror removals** also removes on YouTube Music what you remove on Spotify. A run that would
  remove a large part of the playlist stops and asks first.
- **Keep the source order** puts the YouTube Music playlist in Spotify's order after adding.
- Tracks Trackhop could not find with certainty go to the **review**: pick one of the suggestions or
  paste a link, and the choice is remembered for next time.
- Music videos, live versions and remasters: the matcher compares title, artists and duration, and
  prefers the official audio.
- With Spotify's developer key, Spotify only lets an app read the playlists **you** created or
  collaborate on. For a playlist made by someone else, use the quick route, or copy its tracks into
  a playlist of yours first.

## The other way round

The same steps work from YouTube Music to Spotify, or both ways at once: a **two-way** sync keeps
the two playlists the same whichever side you edit.

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
