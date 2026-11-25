---
title: Move a YouTube playlist to Spotify
description: Turn a YouTube playlist of music videos into a Spotify playlist, from a public link or your Google account, and keep it in sync. Video titles are read as artist and song. Free and open source.
---

# Move a YouTube playlist to Spotify

A YouTube playlist is a list of **videos**, not songs: "Artist - Song (Official Video)", lyric
videos, live clips, the odd fan upload. Trackhop reads each video title as an artist and a song,
finds that song on Spotify, and puts the ones it is not sure about in front of you instead of
guessing. Free and open source, no Trackhop account.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android 8.0 or newer, or
  [Trackhop Desktop](desktop) / the [Docker server](docker).
- The YouTube playlist: its **public link** is enough. A private one is read through your Google
  account (connect *YouTube Music*: the playlists you made on YouTube are in its library too).
- A Spotify account (free or Premium).

## Step by step

1. **Copy the playlist's link** on YouTube (*Share › Copy link*: `youtube.com/playlist?list=…`).
2. **Connect Spotify** (*Accounts → Add connector → Spotify*).
3. *Syncs → New sync*: as the source pick **From link** and paste the YouTube link (or share it to
   Trackhop from the YouTube app); target *Spotify* and *create a new playlist*.
4. **Open the preview**: it shows the song each video was read as, before anything is added.
5. **Run it**, then check the **review**.

## How video titles are read

- `Daft Punk - Get Lucky (Official Video)` becomes *Get Lucky* by *Daft Punk*: the words in
  brackets about the video (*Official Video*, *Lyrics*, *HD*, *Visualizer*) are dropped.
- When a title has no "Artist - Song" shape, the channel is taken as the artist, which is right for
  official artist channels and wrong for compilation channels: those songs land in the review.
- A **live**, **cover** or **remix** stays one: Trackhop does not swap a live clip for the studio
  song, or the other way round, without asking.
- What is not a song (a podcast episode, a DJ mix of an hour, a vlog) is reported as not found.
  Tick *ignore* in the review and it is not asked again.

## Good to know

- A scheduled sync follows the YouTube playlist: videos added there reach Spotify at the next run.
- Spotify's playlist ends up in YouTube's order with **Keep the source order**.
- Going the other way, Spotify to YouTube, works the same: [Spotify to YouTube Music](spotify-to-youtube-music).

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
