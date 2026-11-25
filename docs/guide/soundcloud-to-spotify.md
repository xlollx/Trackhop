---
title: Move SoundCloud likes and sets to Spotify
description: Copy your SoundCloud likes or a set to Spotify, from a public link or your account. Official releases are found, uploads that exist only on SoundCloud are listed for you. Free and open source.
---

# Move SoundCloud likes and sets to Spotify

SoundCloud is full of music that is **nowhere else**: remixes, edits, DJ sets, demos. Copying your
likes to Spotify is therefore half a transfer: Trackhop finds the official releases, and tells you
plainly which tracks Spotify does not have, instead of filling the gaps with the wrong songs.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) from GitHub or
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), [Trackhop Desktop](desktop) or the
  [Docker server](docker) for public links. The Google Play and AppGallery apps read SoundCloud
  through the [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest) plugin.
- A **public link** to your likes or a set, or your SoundCloud account (beta, in the app).
- A Spotify account.

## Step by step

1. Copy the link of your **Likes** (`soundcloud.com/you/likes`) or of a set; a private set works
   through its secret link. Or connect SoundCloud in *Accounts*.
2. **Connect Spotify**.
3. *Syncs → New sync*: source *From link* with the SoundCloud link (or your SoundCloud account),
   target *Spotify* and *create a new playlist*.
4. **Preview**, run, and open the **review**.

## What is found, and what is not

- **Official releases** uploaded by labels usually carry their ISRC: those are found exactly.
- A title written as "Artist - Song" is split; otherwise the uploader counts as the artist. A
  repost from a fan channel may need a choice in the review.
- **Remixes, edits and DJ sets** are not swapped for the original song: a remix is a different
  song. If Spotify does not have it, it is listed as not found, and Trackhop marks the tracks the
  service has no trace of so you can skip them at a glance.
- Every song not found waits in *Tools › Saved matches › To fix*: a choice made there is used by
  every sync.

## Keep it going

Schedule the sync and new likes on SoundCloud reach Spotify by themselves. To see what a Spotify
playlist still misses compared with a file of your set, [Compare two playlists](/compare) does it
in your browser.

[SoundCloud links](soundcloud) · [Getting started](getting-started) · [All guide pages](./)
