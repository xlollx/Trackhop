---
title: Gaana playlists to Spotify, YouTube Music, Apple Music or any service
description: Move your Gaana playlists and favourite songs to Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL or your own server with Trackhop, and keep them in sync. Quick sign-in on Gaana's own page, or a public playlist link with no account. Free and open source.
---

# Gaana: your playlists, and public playlists as a source

Gaana has no official interface for apps like Trackhop, so Trackhop talks to gaana.com the way its
own pages do. There are two ways in:

- **Quick sign-in** (alpha): your own playlists and your favourite songs, read and written. Gaana
  can be the source or the destination of a sync with Spotify, YouTube Music, Apple Music, Amazon
  Music, TIDAL, Deezer, Navidrome, Jellyfin, Plex or a playlist file.
- **A public playlist link**, with no Gaana account: read-only, as the source of a sync.

Only in the GitHub/F-Droid build, in Docker and in Trackhop Desktop downloaded from this site. The
store builds carry official connectors only: on Google Play it works through the optional
connector plugin. Coming in the next update.

## Sign in

1. In Trackhop, **Accounts › Add › Gaana (web)**. Read the notice: this is not an official route,
   it is against Gaana's terms and it can stop working when the site changes.
2. Gaana's own page opens inside the app. Sign in there as you always do (phone number, Google or
   email). Trackhop never sees your password: it keeps the session the page gets, encrypted on
   your device.
3. The screen closes by itself once the session works.

If the sign-in does not finish in the app (Google can refuse a sign-in inside another app): on a
computer, sign in to gaana.com, open the developer tools (F12) › **Network**, type `token=` in the
filter, right-click a request to `api.gaana.com` › **Copy as cURL**, bring it to the phone's
clipboard and tap **Paste** on the sign-in screen. On the [server](docker#gaana-unofficial) and in
Trackhop Desktop the session is always pasted this way.

## Use a public link

1. In Gaana, open the playlist and **Share → Copy link**. Links like
   `https://gaana.com/playlist/…` are recognised.
2. In Trackhop, start a new sync and paste the link as the source (or share it to Trackhop from
   the Gaana app). Pick the destination as usual.
3. The sync runs like any other, by hand or on a schedule: when the Gaana playlist changes, so
   does your copy.

On the [server](docker) and in Trackhop Desktop, pick **Gaana (public links, unofficial)** as the
source of a new sync and paste the link in the next step.

## What to expect

- **Alpha.** The sign-in and the writing are new and Gaana's site can change at any time.
- **Matching** is exact where it can be: Gaana gives each song's ISRC, the recording's own code,
  which Spotify, Apple Music, Deezer and TIDAL know too. Towards Gaana, Trackhop searches by title
  and artist and takes the result with the same ISRC; without one it compares title and artists,
  and what is unsure ends up in the review, where you pick the right one.
- **With an account**: playlists of your own (create, add, remove, rename, delete) and your
  favourite songs as liked songs. Playlists you only follow are not listed.
- **Public links** are read-only, and playlists only: albums and songs are not read from a link.
  Gaana's own charts list fewer songs than the number they show; Trackhop reads the songs listed.
- **Outside India** part of Gaana's catalogue may not play in the app; the lists are read the same.

## When it fails

- *"the Gaana session has ended: sign in again"*: Gaana signed that session out (you signed out in
  the browser, or too many devices). Sign in again from **Accounts**.
- *"This Gaana playlist is private or doesn't exist: only public links work"*: the playlist is
  private, was deleted, or the link is mistyped.
- *"Gaana changed its site; Trackhop needs an update"*: the site changed something. Update
  Trackhop when a new version is out, or [report it](reporting-a-problem).

See also [Shared links](shared-links) and [JioSaavn](jiosaavn).

[All guide pages](./)
