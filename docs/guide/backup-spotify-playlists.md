---
title: Back up your Spotify playlists automatically (Apple Music, YouTube Music and more too)
description: Back up every Spotify, Apple Music, YouTube Music, TIDAL or Deezer playlist and your liked songs automatically, as dated files in a folder you choose. Free, open source, no account.
i18n: backup
---

# Back up all your playlists, automatically

A playlist you built over years can vanish in a second: deleted by mistake, emptied by a bad sync,
lost with an account you can no longer open, or simply thinned out as songs leave the catalogue.
The music services keep no history you can go back to. **Trackhop** saves every playlist and your
liked songs as dated files, by hand or on a schedule, in a folder you choose. It is free, open
source, and it needs only the one service you already use.

## What gets saved

- **Every playlist** of each connected service, the ones you made and the ones you follow, plus
  your **liked songs** and saved albums and artists.
- Each track with its title, artists, album, duration and, where the service has it, the ISRC
  code: enough to find the same song again anywhere.
- **Dated versions**: each backup adds a new version of a playlist that changed; the last 5 of each
  are kept, and a playlist that has not changed is not written again.
- Optionally your **whole Trackhop set-up** (syncs, settings, the matches you chose) in one
  `.trackhop` file. Your sign-ins and passwords are never in it.

Nothing is changed on the service: a backup only reads.

## How do I back up my Spotify playlists?

1. **Install Trackhop** on Android ([download](/)), or [Trackhop Desktop](desktop) for Windows,
   macOS and Linux.
2. **Connect your service**: *Accounts → Add connector*, then Spotify, Apple Music, YouTube Music,
   Amazon Music, TIDAL, Deezer, Qobuz, or your own Navidrome, Jellyfin, Emby or Plex. You sign in on
   the service's own page.
3. Open *Tools → Backup & restore* and tap **Back up all playlists now**. Every connected service is
   read and saved as files in *File playlists*.
4. Under **Automatically**, set the **Automatic schedule** to *Daily* or *Weekly*.
5. Tap **Backup to a folder → Choose a folder**: an SD card, a Nextcloud or Drive folder, any app
   that offers one. The new files and the set-up of the day are copied there after each backup, so
   they survive even if you lose the phone.

The row shows the date of the **last backup**. If the folder can no longer be reached (a card
removed, access lost), Trackhop tells you and asks for a folder again.

## How do I restore a deleted playlist?

1. Open *Tools → File playlists → Import file* and pick the playlist's file from your backup
   folder (or use the copy already in *File playlists*).
2. Make a **New sync** with that file as the **Source** and your service as the **Target**, with
   *Create a new playlist*.
3. Run it. Trackhop looks each song up, by ISRC first, and puts the ones it is unsure of in a
   review for you to confirm.

The same file can go to **any** service: a backup of your Spotify playlists is also the way to
bring them to YouTube Music, TIDAL or Apple Music one day, if you ever switch.

## Which songs left my playlist?

The **Discover** tab has a page called **Vanished songs**: it compares your dated backups and lists
the songs that were in an older copy of a playlist and are missing from the newest one, with a
quick way to search for them again. It only works if you back up now and then, which is one more
reason to set the schedule.

## Apple Music, YouTube Music and the others

Backups work the same way on every service Trackhop connects to. A few notes:

- **YouTube Music** through Google's official API spends part of its daily quota on a full backup;
  a weekly schedule is plenty.
- **Apple Music** needs an active subscription for apps to read your library.
- **Your own server** (Navidrome, Jellyfin, Emby, Plex): playlists live on your disk already, but a
  dated copy still protects them from a bad edit.

## What format are the files?

Backups are plain **CSV** files, one per playlist, that open in any spreadsheet. To get a single
playlist as **M3U**, **XSPF**, **JSPF** or plain text, use *Tools → Export a playlist*; see
[Export a playlist to CSV or M3U](export-playlist-csv).

## Without a phone

[Trackhop Desktop](desktop) and the [Docker server](docker) have the same full backups, also on a
schedule: handy on a computer or NAS that is always on.

## More you can do with one service

- [Save Discover Weekly before it changes](save-discover-weekly)
- [Remove duplicate songs from a playlist](remove-duplicates-spotify)
- [Fix greyed-out songs](fix-greyed-out-songs)
- [Your Wrapped, whenever you want](wrapped-anytime)

[Download Trackhop](/) · [Getting started](getting-started) · [All guide pages](./)
