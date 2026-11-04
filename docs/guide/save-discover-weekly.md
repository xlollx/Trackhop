---
title: Save Spotify Discover Weekly, Release Radar and daylist before they change
description: Keep the songs of Discover Weekly, Release Radar or your daylist before Spotify replaces them. A free app copies them into a playlist of your own, every week, automatically.
i18n: discover-weekly
---

# Save Discover Weekly before it changes

Discover Weekly is gone every Monday. Release Radar is replaced every Friday, and a daylist changes
several times a day. If you did not save the songs you liked in time, they are gone with it.
**Trackhop** copies these playlists into a playlist that belongs to you, by hand or on a schedule,
so nothing slips away. It is free and open source, and it works with a single Spotify account: no
second service needed.

## Two ways to keep them

| You want | Set the sync to | What you get |
|---|---|---|
| **Every song ever suggested**, piling up week after week | *Add only* (the default) | One playlist that only grows: each run adds the new songs, nothing is ever deleted |
| **A copy that follows it**, on the same or another service | *Mirror removals* + *Playlist that changes by itself* | The target becomes the new version each time, with its title and, on Spotify, its cover |

Most people want the first one: a growing archive of everything Discover Weekly ever gave you.

## How do I save Discover Weekly automatically?

1. **Install Trackhop** on Android ([download](/)), or use [Trackhop Desktop](desktop) on Windows,
   macOS or Linux.
2. **Connect Spotify with the quick sign-in**: *Accounts → Add connector → Spotify (web)*, and
   sign in on Spotify's own page with your usual account.

   > **Why "Spotify (web)" and not the other Spotify connection?** Trackhop has two ways to reach
   > Spotify. The official one, with your own free developer key, can no longer read the
   > playlists that Spotify makes for you (Discover Weekly, Release Radar, daylist): Spotify
   > stopped allowing it in February 2026. With that connection those playlists look empty or
   > are missing; that is Spotify's rule, not a fault in Trackhop. The quick sign-in goes through
   > Spotify's own web player and sees them. It is not an official interface, so read
   > [its notes](troubleshooting#spotify) first. On the Google Play version it comes with the free
   > [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest) plugin.
3. **New sync**: *Syncs → New sync*. As the **Source**, pick *Discover Weekly* from your Spotify
   playlists.
4. As the **Target**, pick Spotify again and *Create a new playlist*, for example "Discover Weekly
   archive". Source and target can be on the same service.
5. **Leave Mirror removals off.** That is the *Add only* mode: the archive keeps every song, even
   after Discover Weekly has dropped it.
6. **Automatic schedule**: *Weekly*, or *On chosen days* with Tuesday ticked and a time of your
   choice, so the run comes after Spotify's Monday refresh.
7. **Save**, check the preview, and tap **Run now** for the first copy. From then on it runs by
   itself.

<!-- update when archive mode ships -->

Each week the card shows how many songs were added. Tracks already in the archive are not added
twice.

## Release Radar and the daylist

The same sync works for any playlist that renews itself:

- **Release Radar** refreshes on Fridays: pick *On chosen days* with Saturday ticked.
- **daylist** changes through the day: *Every 6 hours* catches most of its moods, *Every hour*
  catches nearly all of them. In *Add only* mode you get one long playlist of every daylist.
- **Your Mixes, Daily Mix, On Repeat**: same steps, any schedule you like.

## A copy that follows it, title and cover included

If you would rather have the current Discover Weekly also as a playlist of your own (to share it,
or to listen to it on another service), turn on **Mirror removals**. A second switch appears:
**Playlist that changes by itself**. With it on, the target becomes the source's new version every
time, even when most tracks change, with no confirmation to give. A daylist's new title follows
along, and on Spotify its new cover too, unless you gave the target a name of your own.

Without that switch, Trackhop treats a playlist that loses most of its songs as a possible mistake
and holds the removals back for you to confirm: the right thing for your own playlists, not for
Discover Weekly.

## Can I keep one playlist per week?

Yes, by hand: *Tools → New playlist from… → Copy* makes a one-off copy of this week's Discover
Weekly as a new playlist, untouched afterwards. And if you turn on the automatic
[backups](backup-spotify-playlists), every backup also keeps a dated copy of Discover Weekly as a
file, so you can look back at the last few weeks.

## Does it work with only Spotify?

Yes. Everything above stays inside one Spotify account. If you also use YouTube Music, TIDAL,
Deezer, Apple Music or your own Navidrome, Jellyfin or Plex, pick that as the target instead and
your Discover Weekly lands there.

## Is it safe for my account?

You sign in on Spotify's page, and the sign-in stays encrypted on your phone. Trackhop has no
server and no account of its own. A weekly sync reads one playlist and adds a few dozen songs: the
same thing you would do by hand, once a week.

## More you can do with one service

- [Back up all your playlists automatically](backup-spotify-playlists)
- [Remove duplicate songs from a playlist](remove-duplicates-spotify)
- [Fix greyed-out songs](fix-greyed-out-songs)
- [Your Wrapped, whenever you want](wrapped-anytime)

[Download Trackhop](/) · [Getting started](getting-started) · [All guide pages](./)
