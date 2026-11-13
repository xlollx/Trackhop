---
title: NetEase Cloud Music and QQ Music playlists to Spotify, Apple Music or YouTube Music
description: Move a public NetEase Cloud Music (网易云音乐) or QQ Music playlist to Spotify, Apple Music, YouTube Music, TIDAL or your own server with Trackhop, from the playlist's link, with no Chinese account needed. Free and open source.
---

# NetEase Cloud Music and QQ Music: public playlists as a source

[中文说明](netease-qq-music-zh)

Neither NetEase Cloud Music (网易云音乐) nor QQ Music has an official interface for apps like
Trackhop, and neither needs one for this: a **public playlist link** becomes the source of a sync
towards any other service, Spotify, Apple Music, YouTube Music, TIDAL, Deezer, Navidrome, Jellyfin,
Plex or a playlist file, with no account on the Chinese side.

Only in the GitHub/F-Droid build, in Docker and in Trackhop Desktop downloaded from this site. The
Google Play and AppGallery builds reach these links only through the
[Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest) plugin. The Microsoft
Store build carries official connectors only, so it does not have them.

## Use a link

1. In the NetEase Cloud Music or QQ Music app, open the playlist and **Share → Copy link**. These
   are recognised:
   - NetEase: `https://music.163.com/#/playlist?id=…`, `https://music.163.com/playlist?id=…`,
     the mobile `https://y.music.163.com/m/playlist?id=…` and the app's share text
     (`https://music.163.com/playlist/…/…/?userid=…`);
   - QQ Music: `https://y.qq.com/n/ryqq/playlist/…`, the older `https://y.qq.com/n/yqq/playlist/….html`
     and the app's share link (`https://i.y.qq.com/n2/m/share/details/taoge.html?…&id=…`).
2. In Trackhop, start a new sync and paste the link as the source (or share it to Trackhop from
   the music app: the editor opens with it). Pick the destination as usual.
3. The sync runs like any other, by hand or on a schedule: a playlist that grows on the Chinese
   side keeps growing on the other.

On the [server](docker) and in Trackhop Desktop, pick **NetEase Cloud Music (public links,
unofficial)** or **QQ Music (public links, unofficial)** as the source of a new sync and paste the
link in the next step.

## What to expect

- **Read-only.** NetEase and QQ Music are never destinations: nothing is ever written there, and
  Trackhop never asks for a Chinese account.
- **Matching** is by title, artist and duration; there is no ISRC on either side. Chinese,
  Japanese and Korean releases usually carry the same title on Spotify, Apple Music and YouTube
  Music, so most tracks match at once; a track with a romanised or translated title elsewhere ends
  up in the review screen, where you pick the right one.
- **Greyed-out tracks.** A NetEase track that is no longer licensed there is listed anyway and
  marked unavailable, like Spotify's greyed-out tracks: it is still searched on the destination.
- **Long playlists** are read whole, up to 10,000 tracks (NetEase lists them all in one call and
  fetches the details in batches; QQ Music hands them over 30 at a time).

## When it fails

- *"This NetEase Cloud Music playlist is private or doesn't exist: only public links work"* (or
  the same for QQ Music): the playlist is private, was deleted, or the link is mistyped. Ask its
  owner to make it public, or share it from the app again.
- *"NetEase Cloud Music changed its site; Trackhop needs an update"* (or QQ Music): Trackhop reads
  the link the way the site's own pages do, not through an official interface, and the site
  changed something. Update Trackhop when a new version is out, or [report it](reporting-a-problem).
- The link is recognised but belongs to the other service: pick the matching source first. In the
  app the source is chosen from the link itself, so this only happens on the server's form.

[Back to the guide](./)
