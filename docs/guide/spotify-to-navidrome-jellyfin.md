---
title: Sync Spotify playlists to Navidrome, Jellyfin or Plex
description: Rebuild your Spotify playlists on your own Navidrome, Subsonic, Jellyfin or Plex server from your local library, and keep them in sync. Self-hosted friendly.
---

# Sync Spotify playlists to Navidrome, Jellyfin or Plex

You discover music on Spotify but listen from your own server. Trackhop rebuilds your Spotify
playlists on **Navidrome** (or any Subsonic server: Gonic, Airsonic, Ampache), **Jellyfin** or
**Plex**, using the tracks in your own library, and keeps them in step as the Spotify playlist
changes. It runs on your phone, or on the server itself with [Docker](docker). Trackhop is listed in the
[Navidrome app catalog](https://www.navidrome.org/apps/).

## What you need

- [Trackhop](https://github.com/xlollx/Trackhop/releases/latest) on Android, or the
  [Docker image](docker) on the server, or [Trackhop Desktop](desktop).
- The server's address and your login. Nothing to install on the server.

## Step by step

1. **Connect Spotify** (*Accounts → Add connector → Spotify*).
2. **Connect your server**: *Navidrome / Subsonic*, *Jellyfin* or *Plex*, with its address, user and
   password. On Ampache use the Subsonic password from your preferences, not the account password.
3. *Syncs → New sync*: source *Spotify* and the playlist, target your server and *create a new
   playlist*.
4. **Preview** and run. Every track found in your library is added in order.

## What about songs I do not own?

They are listed as **not found**, which doubles as a shopping list: get the album, let the server
scan it, and the next run adds it to the playlist by itself.

## Good to know

- Matching compares title, artists, album and duration, so different tags and remasters still
  match; the review lets you pick by hand when two files are close.
- The other way works too: a playlist made on your server pushed to Spotify or YouTube Music to
  share it with friends.
- With Docker the syncs run on the server on a schedule, phone on or off.

[Docker guide](docker) · [Getting started](getting-started) · [All guide pages](./)
