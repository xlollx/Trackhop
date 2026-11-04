---
title: Remove duplicate songs from Spotify playlists (and YouTube Music, TIDAL, Deezer)
description: Find and remove duplicate songs in a Spotify, YouTube Music, TIDAL, Deezer or Amazon Music playlist, even when the copies have different IDs or come from another album. Free and open source.
i18n: duplicates
---

# Remove duplicate songs from a playlist

Add songs to a playlist for long enough and the same track turns up twice: once from the album,
once from a single or a compilation, once from a share of a friend. The service sees two different
tracks, so it does not warn you, and its own "already added" check only catches the exact same
copy. **Trackhop** finds the repeats, shows them to you, and removes the extra copies in one go. It
is free, open source, and it works inside the one service you use.

## What counts as a duplicate?

Trackhop groups two entries as the same song when:

- they are **the same track**, added twice;
- they share the same **ISRC**, the code that identifies a recording, even if one comes from the
  album and the other from a "Best of";
- or they have the **same title and the same artists**, with a compatible duration.

A **live**, **acoustic** or **remixed** version stays a song of its own: "Throne" and "Throne – Live
at the Royal Albert Hall" are not duplicates. In each group the **first copy** in the playlist is
kept and the others are removed, so the playlist's order does not change.

## How do I remove duplicates from a Spotify playlist?

1. **Install Trackhop** on Android ([download](/)), or [Trackhop Desktop](desktop) for Windows,
   macOS or Linux.
2. **Connect Spotify**: *Accounts → Add connector → Spotify*, signing in on Spotify's own page.
3. Open **Tools**. At the top, under *Service for the tools*, pick Spotify.
4. Under **Clean up**, tap **Remove duplicates**.
5. Choose the playlist and tap **Find duplicates**. Trackhop reads it and lists each group: the
   copy that stays, and the ones that would go.
6. Check the list, then tap **Remove *n* tracks**. That's it.

Nothing is removed before you tap that button, and only playlists you own are listed (a playlist
someone else made cannot be edited by any app).

## YouTube Music, TIDAL, Deezer, Amazon Music and your own server

The same tool works on every service that lets apps remove tracks from a playlist: Spotify, YouTube
Music, TIDAL, Deezer, Amazon Music, Qobuz, Navidrome and other Subsonic servers, Jellyfin, Emby and
Plex. Pick the service at the top of *Tools* and follow the same steps.

## And on Apple Music?

Apple does not let apps remove tracks from a playlist, so *Remove duplicates* is greyed out there.
You can still get a clean copy: *Tools → New playlist from… → Copy*, choose the playlist and tick
**Skip duplicates**. Trackhop creates a new playlist with one copy of each song; delete the old one
in the Music app afterwards if you like.

## Merging playlists without making new duplicates

To put several playlists together, *Tools → New playlist from… → Merge* with **Skip duplicates**
ticked: you get one playlist with one copy of each song, and the originals stay untouched. More on
this in [Merge playlists and remove duplicates](merge-playlists-remove-duplicates).

## The same song in several playlists

Duplicates inside one playlist are one thing; the same song scattered over ten playlists is
another. **Repeats across playlists** (also in *Tools*) reads all your playlists once and shows
the songs that sit in more than one, and the liked songs that are in none. It changes nothing; it
just tells you.

## Can I undo it?

Removing duplicates removes only the extra copies, and the first one of each song stays, so the
music is still in the playlist. If you want a safety net anyway, tap **Back up all playlists now**
in *Tools → Backup & restore* before you start: the backup keeps the playlist as it was, ready to
restore. See [Back up your playlists automatically](backup-spotify-playlists).

## More you can do with one service

- [Fix greyed-out songs](fix-greyed-out-songs), the other half of *Clean up*
- [Save Discover Weekly before it changes](save-discover-weekly)
- [Back up all your playlists](backup-spotify-playlists)
- [Your Wrapped, whenever you want](wrapped-anytime)

[Download Trackhop](/) · [Getting started](getting-started) · [All guide pages](./)
