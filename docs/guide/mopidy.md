---
title: Mopidy playlist sync
description: Keep the playlists of your Mopidy server in sync with Spotify, YouTube Music, Apple Music, TIDAL, Deezer, Navidrome or Jellyfin, with Trackhop. Free and open source, no account.
---

# Mopidy (beta)

[Mopidy](https://mopidy.com) is a music server that plays your files and more, often on a Raspberry
Pi. Trackhop reads and writes its **playlists**, as a source and as a destination, matched against
what your Mopidy can find by title, artists and duration.

In every build: the Android app (Google Play included), [Desktop](desktop) and [Docker](docker).
Trackhop only talks to the server you enter, through Mopidy's own documented API (JSON-RPC over
HTTP). Nothing unofficial, no account anywhere.

## Before you connect

Mopidy's web port answers only the computer it runs on until you say otherwise. In `mopidy.conf`:

```ini
[http]
enabled = true
hostname = 0.0.0.0
port = 6680
```

Restart Mopidy. Mopidy has no sign-in: whoever reaches that port can use it, so keep it on your
home network.

## Connect it

1. In Trackhop open **Services**, add **Mopidy**.
2. Enter the server's address with its port: for example `http://192.168.1.10:6680`. There is no
   user or password to enter.

On the [server](docker) page it is the same form, under **Services**.

## What works

| | |
|---|---|
| Read playlists and their tracks | Yes |
| Create a playlist | Yes |
| Add tracks | Yes |
| Remove tracks (Mirror) | Yes |
| Delete a playlist | Yes |
| Rename a playlist | No: Mopidy gives a renamed playlist a new address, and the syncs would lose it |
| Favourites, albums, artists | No: only playlists |

## Good to know

- **Playlists are files** (M3U) named after them. Trackhop never creates a playlist over one with
  the same name: it says so and you pick the existing one, or another name.
- **Matching** searches whatever your Mopidy searches: the local library (Mopidy-Local) and any
  other extension you installed. Scan your files first (`mopidy local scan`). No ISRC is listed, so
  there is no exact match by code.
- A playlist is saved whole after each change, so do not edit it from another app while a sync runs.
- It is marked **beta**: written on the documented API and tested against a stand-in server. If
  something does not behave as written here, [report it](reporting-a-problem).

## When it fails

- *"No Mopidy server answered at this address"*: check the address and the port (6680 unless you
  changed it), the `hostname` setting above, and that the phone is on the same network.

See also: [Self-hosted playlist sync](self-hosted-playlist-sync).

[Back to the guide](./)
