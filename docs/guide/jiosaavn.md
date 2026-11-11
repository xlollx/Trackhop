---
title: JioSaavn playlists to Spotify, YouTube Music, Apple Music or any service
description: Move a public JioSaavn playlist, album or song to Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL or your own server with Trackhop, from its link, with no JioSaavn account. Free and open source.
---

# JioSaavn: public playlists as a source

JioSaavn has no official interface for apps like Trackhop, and none is needed for this: the link of
a **public playlist, album or song** becomes the source of a sync towards any other service,
Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer, Navidrome, Jellyfin, Plex or a
playlist file, with no JioSaavn account.

Only in the GitHub/F-Droid build, in Docker and in Trackhop Desktop downloaded from this site. The
store builds carry official connectors only, so they do not have it.

## Use a link

1. In JioSaavn, open the playlist, album or song and **Share → Copy link**. Links like these are
   recognised: `https://www.jiosaavn.com/featured/…/…`, `https://www.jiosaavn.com/s/playlist/…/…/…`,
   `https://www.jiosaavn.com/album/…/…` and `https://www.jiosaavn.com/song/…/…`.
2. In Trackhop, start a new sync and paste the link as the source (or share it to Trackhop from
   the JioSaavn app). Pick the destination as usual.
3. The sync runs like any other, by hand or on a schedule: when the JioSaavn playlist grows, so does
   your copy.

On the [server](docker) and in Trackhop Desktop, pick **JioSaavn (public links, unofficial)** as the
source of a new sync and paste the link in the next step.

## What to expect

- **Read-only.** JioSaavn is never a destination: nothing is written there.
- **Matching** is by title, artists, album and length: JioSaavn gives no ISRC. Most Bollywood,
  Punjabi and Tamil releases carry the same title on Spotify, YouTube Music and Apple Music; a song
  written differently elsewhere ends up in the review, where you pick the right one.
- **Long playlists** are read page by page, a second apart, up to 10,000 songs.

## When it fails

- *"This JioSaavn link is private or doesn't exist: only public links work"*: the playlist is
  private, was deleted, or the link is mistyped.
- *"JioSaavn changed its site; Trackhop needs an update"*: Trackhop reads the link the way the
  site's own pages do, not through an official interface, and the site changed something. Update
  Trackhop when a new version is out, or [report it](reporting-a-problem).

See also [Shared links](shared-links) and [NetEase Cloud Music and QQ Music](netease-qq-music).

[All guide pages](./)
