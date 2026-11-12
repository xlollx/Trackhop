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

An **OPML** file of podcasts from AntennaPod, Pocket Casts or Podcast Addict imports like a
playlist file, and a sync to *Followed podcasts* on Spotify or Deezer follows its shows there.
*Coming in the next update:* your followed podcasts exported as OPML too; when a service does not
give a show's feed, it is looked up in Apple's public podcast directory.

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

Once imported, *Send to a service* turns a list into the source of a new sync, with the first
service that can make playlists as the target: it is offered right after an import or a pasted list,
and in each list's ⋮ menu. On Desktop and Docker it is the *Send to a service* link next to each file.

To tidy up, hold a list to select several (*Select shown* takes every list on screen), then *Export
to a folder* in the format you choose, or delete them at once. On Desktop and Docker, tick the files
and download them as one `.zip` or delete them.

M3U, PLS and text files often carry only a name per track. "Artist - Title" is read as it is
written; names in three parts, "Artist - Album - Title", are read that way when most lines of the
file have three parts, so an occasional "Song - Radio Edit" stays part of the title. To choose it
yourself: the File card's ⋮ menu › *Track names in files* in the app, or the choice under the upload
button on Desktop and Docker. It applies to the next import: import a file again to read it anew.

An M3U that lists only file paths is read from its folders: the artist is the folder below *Music*
(folders in between, such as a discography, are skipped), the album the folder of the file (a
*Disc 1* folder counts as the album above it; a year before the name, "1977 - ", is left out).

For any other layout, write your own patterns (*Your own patterns…* in the same menu, or under the
upload button): one per line, tried in order, matched against the end of the path or name (the
file's extension left out), so the folders above do not need writing. A line none of them reads is
read as usual.

- `{artist}`, `{album}`, `{title}` (needed) and `{albumartist}` are read; any other `{name}`, such as
  `{track}`, `{year}`, `{disc}` or `{genre}`, is skipped, as is `*` (any text) and `**/` (any
  number of folders).
- `[ … ]` is an optional part (`\[` and `\]` are brackets); `/` goes between folders.
- Spaces match spaces or underscores, a dash matches -, – or — with or without spaces, capitals do
  not matter, and a placeholder written twice is read the first time.

```
{artist}/{album}/{track} {title}
Music/{artist}/**/{year} - {album}/[Disc {disc}/]{track} {title}
{artist}/{album}/{artist} - {title}
{artist} - {title}[ ({year})]
{title} ({artist})
```

With `**/` between the artist and the album, start from the folder above the artist (`Music/`),
or the first folder of the path is read as the artist. Or a regular expression after `re:` with
named groups, such as `re:^(?<artist>.+?) _ (?<title>.+)$`. To check them, paste a line from your
file under *Try a line from your file* (the app shows at once what it reads; on Desktop and Docker,
*Try the patterns*).

[Getting started](getting-started) · [All guide pages](./)
