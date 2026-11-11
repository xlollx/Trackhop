---
title: Songs missing from your Navidrome, Jellyfin, Emby or Plex library, as a list for Lidarr
description: See which songs of your Spotify, Apple Music, TIDAL or YouTube Music playlists your own music server (Navidrome, Jellyfin, Emby, Plex, Lyrion, Mopidy) does not have yet, by album, and export them as CSV, M3U, text or a Lidarr custom list. Free, in the Trackhop app, Desktop and Docker.
---

# Missing on my server: what your library still lacks

You sync your Spotify, Apple Music or TIDAL playlists to your own [Navidrome, Jellyfin or
Plex](spotify-to-navidrome-jellyfin), and some songs aren't found: you simply don't have them yet.
**Missing on my server** gathers them into one list, so you know what to look for.

## Where it is

- In the app: **Tools › Missing on my server**.
- On [Desktop](desktop) and [Docker](docker): **Tools › Missing on my server** on the web page,
  the [API](docker#api) (`GET /api/v1/wanted`), the [command line](cli) (`trackhop wanted`) and the
  MCP tool `list_missing_on_server`.

It works for syncs towards Navidrome and other Subsonic servers, Jellyfin, Emby, Plex,
[Lyrion](lyrion) and [Mopidy](mopidy).

## What the list shows

The songs your syncs to your server could not find on their last runs, **each song once**, even
when several syncs or services want it: artist, title, album, length, and which syncs want it and
since when. Nothing is looked up for the list itself: it is made from what the syncs already did.

- **By album** groups the songs, so you can see that a whole album is missing.
- **Tick** a song once you got it: it leaves the list (**Show what I got** brings the ticked ones
  back into view). A song your next sync finds leaves the list by itself.
- **Export the list** as CSV, M3U, plain "Artist - Title" text or JSON.
- **List for Lidarr**: the list in the shape of Lidarr's *Custom List*. On Docker and Desktop,
  Lidarr can read it straight from the server with a key of its own that opens only this list.

Trackhop downloads and buys nothing: what you do with the list is up to you.

## Match more of what you have

If your files carry **MusicBrainz tags** (written by MusicBrainz Picard or beets), Trackhop matches
each streaming track to the exact recording on your server, even when its title is written
differently, so fewer songs end up on this list by mistake. **Tools › MusicBrainz tags** lists the
songs on your server without them. More in [Self-hosted playlist sync](self-hosted-playlist-sync).

[All guide pages](./)
