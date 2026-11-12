---
title: Two-way playlist sync between Spotify, YouTube Music, Apple Music and more
description: Keep one playlist the same on two services in both directions with Trackhop, additions and, if you want, removals too. Spotify, YouTube Music, Apple Music, TIDAL, Deezer, Navidrome, Jellyfin and more. Free and open source, no account.
---

# Two-way playlist sync

A normal sync goes one way: the source is the master and the target follows it. A **two-way** sync
keeps two playlists the same whichever side you change: a song added on Spotify reaches YouTube
Music, and a song added on YouTube Music reaches Spotify.

## Set it up

1. Make a sync between two **existing** playlists of yours (a two-way sync needs to write on both
   sides, so not a public link or someone else's playlist).
2. In the editor, turn on **Two-way**. Trackhop adds the reverse sync, shown as a card marked ↔.
3. Pick the schedule as usual: both halves share it, with the same rules, colour, label, tags and
   star. Editing the ↔ card opens the original sync, and deleting or turning off either acts on both.

## Removals too

By itself, two-way only adds: a song you delete on one side comes back from the other on the next
run. Turn on **Removals too** and a song you remove on one side is removed on the other as well.

- It starts after the next run, which notes the songs both sides have. Only songs that were on
  both sides and then disappeared from one count as removed.
- A song the service hides or no longer plays is not treated as removed.
- When a run would remove a lot at once, the removals are held back, as with mirror removals: the
  card says so, and you confirm them in the preview with **Remove these tracks**. Scheduled runs keep
  holding them back.

## Where it works

In the app, on [Trackhop Desktop](desktop) and on a [Docker server](docker) (the web page, the
[API](docker#api) with `twoWay` and `twoWayRemovals`, and the [command line](cli) with `--two-way`).
A two-way pair stays where it was made: [handing a sync over to your server](phone-and-server),
coming in the next update, leaves two-way pairs out.

See also [Keep playlists in sync](sync-playlists) · [All guide pages](./)
