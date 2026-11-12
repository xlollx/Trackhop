---
title: New releases from your playlist's artists
description: See the new albums, EPs and singles of the artists in any playlist of yours, from the last week, month or three months, and put them in a playlist on Spotify, Apple Music, YouTube Music, TIDAL, Deezer, Navidrome or any other service, now or every week. Free, no account.
---

# New releases from your playlist's artists

> **This arrives in the next update** of the app, Desktop and Docker. This page describes it ahead of time.

Pick a playlist you like and **Trackhop** shows what its artists put out lately: the albums, EPs
and singles of the last **7, 30 or 90 days**, and, if you want, the ones **coming soon**. Then put
them in a playlist on any service Trackhop connects to, once or **every week**.

## Where the news comes from

Two public music catalogues, with no account and no key: **Deezer**'s (every album with its
release date) and **MusicBrainz** (the open music encyclopedia, which also knows many releases
announced for later). The same album on both is shown once. Compilations and live albums are left
out: only new music of the artist's own.

Trackhop looks at the playlist's most frequent artists (up to 50) and asks about each one once a
day. MusicBrainz answers one question a second, so a first search of a big playlist takes about a
minute; another window, or another playlist with the same artists, is then instant.

## In the app

**Discover › New releases**:

1. Choose a playlist on a connected service, or paste a link to a public one.
2. Choose **7**, **30** or **90 days**, and **Coming soon** for what is announced.
3. Tap **Find new releases**. Tap a release to open it on Deezer or MusicBrainz.
4. Untick what you don't want, then:
   - **Make a playlist**: the songs become a File playlist and a new sync opens, to send them to any
     service as a new playlist ("New releases from …");
   - or **Add to a playlist**: pick one of your playlists, and a sync opens that adds the ticked
     releases to it, skipping the songs already there.

### Every week

**Every week** makes a sync whose source is the new releases of that playlist's artists. Choose
where it goes, as for any sync: once a week it adds what came out to the playlist, and nothing is
ever removed from it, so it grows into a list of your artists' new music. With
[notification channels](notification-channels) set up (Telegram, ntfy, Discord and more), you are
told when it found something.

## On Docker and Desktop

**Tools › New releases** does the same: a playlist, a window, the list, then **Save the tracks as a
File playlist** (and *Send it to a service*) or **Every week** to a new playlist on a service.

The same from scripts:

- API: `GET /api/v1/releases?service=spotify&playlist=<id>&days=30` (`upcoming=true` for what is
  announced, `tracks=true` for the songs). Its `source` is the source of a weekly sync:
  `POST /api/v1/syncs` with it, `"schedule": "weekly"` and `"changes": "archive"`.
  See [the API](docker#api).
- Command line: `trackhop releases <service> <playlist> [--days 7|30|90] [--upcoming]`
  ([the command line](cli)).
- AI assistants: the MCP tool `find_new_releases` ([MCP](cli#with-an-ai-assistant-mcp)).
