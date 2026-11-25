---
title: Transfer Amazon Music playlists to Apple Music
description: Move Amazon Music playlists and liked songs to Apple Music on Android, and keep them in sync, even though Amazon has no public API. Free and open source.
---

# Transfer Amazon Music playlists to Apple Music

Amazon Music gives other apps no public way to read your playlists, and Apple Music gives them only
a careful one. Trackhop signs in to both **on their own pages, on your phone**, copies your Amazon
Music playlists and liked songs to Apple Music and can keep them in sync. Free and open source.

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) from GitHub or
  [F-Droid](https://f-droid.org/packages/com.xlollx.songport/), on Android 8.0 or newer. With the
  Google Play or AppGallery app, add the [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest)
  plugin for Amazon Music.
- Your Amazon Music account (Prime or Unlimited) and an Apple Music subscription.

Both are phone-only: [Trackhop Desktop](desktop) and [Docker](docker) do not sign in to Amazon
Music or Apple Music.

## Step by step

1. **Connect Amazon Music** (*Accounts → Add connector → Amazon Music*) and sign in on Amazon's page.
2. **Connect Apple Music** with your Apple ID, on Apple's page inside the app.
3. *Syncs → New sync*: source *Amazon Music* and a playlist, or *Liked songs*; target *Apple Music*
   and *create a new playlist*. For all your playlists: *Tools → Transfer many playlists*.
4. **Preview**, run, and check the **review**.

## Good to know

- Songs are matched by ISRC where both services have it, then by title, artists and length; doubtful
  ones go to the review, never in by guesswork.
- Signed in with your Apple ID, a sync also **removes** on Apple Music what left the Amazon
  playlist. With an Apple developer token it cannot: Apple's interface for apps only adds.
- Amazon Music is read through its web player's own interface, not an official one: it may need an
  update when Amazon changes its player.
- Moving to Spotify instead: [Amazon Music to Spotify](amazon-music-to-spotify).

[Getting started](getting-started) · [Troubleshooting](troubleshooting) · [All guide pages](./)
