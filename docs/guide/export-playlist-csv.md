---
title: Export a Spotify or YouTube Music playlist to CSV or M3U
description: Save any playlist from Spotify, YouTube Music, Apple Music, TIDAL or Deezer as CSV, M3U, XSPF or JSON, and back up all your playlists on a schedule. Free and open source.
---

# Export a playlist to CSV or M3U

Trackhop saves a playlist from any connected service as a file you keep: **CSV** for a spreadsheet,
**M3U** or **XSPF** for a music player, **JSON** for scripts. It also backs up **all** your
playlists at once, by hand or on a schedule, so you are never one deleted account away from losing
them.

## Export one playlist

1. Connect the service (*Accounts → Add connector*): Spotify, YouTube Music, Apple Music, TIDAL,
   Deezer, Amazon Music, Navidrome, Jellyfin, Plex…
2. *Tools → Export a playlist*, pick the playlist and the format, and save the file wherever you
   like (phone storage, Drive, Nextcloud…).

## Back up everything

*Tools → Backup & restore → Back up all playlists now* saves every playlist and your liked songs of
every connected service as dated files, keeping the last versions of each. Choose a folder and a
schedule and it happens by itself, with the files copied to a folder outside the phone.

The same page saves your whole Trackhop set-up (syncs, settings, the matches you chose) to one
`.trackhop` file, to restore on a new phone or in [Trackhop Desktop](desktop).

## And back in

A CSV, M3U, XSPF, JSPF, iTunes XML, JSON or plain-text list can also be the **source** of a sync:
*Tools → File playlists → Import*, then sync it to any service. That is how you move a playlist
from a service Trackhop cannot reach, or restore one from a backup.

M3U, PLS and text files often carry only a name per track. "Artist - Title" is read as it is
written; names in three parts, "Artist - Album - Title", are read that way when most lines of the
file have three parts, so an occasional "Song - Radio Edit" stays part of the title. To choose it
yourself: the File card's ⋮ menu › *Track names in files* in the app, or the choice under the upload
button on Desktop and Docker. It applies to the next import: import a file again to read it anew.

[Getting started](getting-started) · [All guide pages](./)
