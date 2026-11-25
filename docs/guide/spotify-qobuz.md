---
title: "Spotify and Qobuz: transfer and sync playlists both ways"
description: Move your Spotify playlists and liked songs to Qobuz, or Qobuz favourites to Spotify, and keep both in sync while you try hi-res. Matched by ISRC. Free and open source.
---

# Spotify and Qobuz: playlists both ways

Trying Qobuz for its hi-res sound, but not ready to leave Spotify? Trackhop copies your playlists
and liked songs to Qobuz, and can keep **both sides in step** while you decide: edit on either
service and the other follows. Free and open source, no Trackhop account.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) from GitHub or
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), [Trackhop Desktop](desktop) or the
  [Docker server](docker). The Google Play and AppGallery apps reach Qobuz through the
  [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge) plugin.
- Your Qobuz account (also one made with Google) and a Spotify account.

## Step by step

1. **Connect Qobuz** (*Accounts → Add connector → Qobuz*) and sign in on Qobuz's own page.
2. **Connect Spotify**.
3. *Syncs → New sync*: source *Spotify* and a playlist or *Liked songs*, target *Qobuz* and *create
   a new playlist*. Liked songs can go to your Qobuz **favourite tracks** instead.
4. For your whole library at once: *Tools → Transfer many playlists*.
5. **Preview**, run, and check the **review**.

## While you try both: a two-way sync

Turn the sync into a [two-way sync](two-way-sync) and the playlist stays the same whichever side
you add to or remove from. When you settle on one service, delete the sync: both playlists stay as
they are.

## Good to know

- Qobuz is searched **by ISRC**, the code of the recording, so a Spotify song is found exactly when
  Qobuz has it, in the same version; the rest goes to the review with suggestions.
- Qobuz is reached through its web player, not an official interface (beta): it may need an
  update when Qobuz changes its player.
- No Qobuz account? A **public Qobuz playlist link** can be the source of a sync to Spotify.

[Getting started](getting-started) · [Two-way sync](two-way-sync) · [All guide pages](./)
