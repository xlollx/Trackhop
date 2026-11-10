---
title: Lyrion Music Server playlist sync (Logitech Media Server, Squeezebox)
description: Keep the playlists of your Lyrion Music Server (the former Logitech Media Server) in sync with Spotify, YouTube Music, Apple Music, TIDAL, Deezer, Navidrome or Jellyfin, with Trackhop. Free and open source, no account.
---

# Lyrion Music Server (beta)

[Lyrion Music Server](https://lyrion.org) is the former Logitech Media Server, the server behind
Squeezebox players. Trackhop reads and writes its **saved playlists**, as a source and as a
destination, matched against your own library by title, artists and duration.

In every build: the Android app (Google Play included), [Desktop](desktop) and [Docker](docker).
Trackhop only talks to the server you enter, through the server's own documented command interface
(JSON-RPC on its web port). Nothing unofficial, no account anywhere.

## Connect it

1. In Trackhop open **Services**, add **Lyrion Music Server**.
2. Enter the server's address with its port, as you open it in a browser: for example
   `http://192.168.1.10:9000`.
3. Leave user and password empty, unless you turned on *Password protection* in the server's
   settings (*Settings → Advanced → Security*): then enter those.

On the [server](docker) page it is the same form, under **Services**.

## What works

| | |
|---|---|
| Read playlists and their tracks | Yes |
| Create a playlist | Yes |
| Add tracks | Yes |
| Remove tracks (Mirror) | Yes |
| Rename and delete a playlist | Yes |
| Favourites, albums, artists | No: only playlists |

## Good to know

- **A playlist keeps each track once.** Lyrion's own playlist editing does not add a track that is
  already there, so a source with the same song twice ends with one copy.
- **Two playlists cannot share a name**, because the server keeps them as files named after them.
  Creating or renaming onto a taken name is refused with a message, never done over the other one.
- **Matching** is against the tracks the server has scanned. With the *Full Text Search* plugin on
  (the default) artist and title are searched together; without it Trackhop searches by title and
  puts the artist's songs first. The server lists no ISRC, so there is no exact match by code.
- **Playlists the server reads from a streaming plugin** are listed like the others; the tracks
  Trackhop adds are always tracks of your own library.
- It is marked **beta**: written on the documented commands and tested against a stand-in server.
  If something does not behave as written here, [report it](reporting-a-problem).

## When it fails

- *"No Lyrion Music Server answered at this address"*: check the address and the port (9000 unless
  you changed it), and that the phone is on the same network as the server.
- *"This server asks for a username and password"*: password protection is on; enter the user and
  password set in the server's security settings.

See also: [Self-hosted playlist sync](self-hosted-playlist-sync).

[Back to the guide](./)
