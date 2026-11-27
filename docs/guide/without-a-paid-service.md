---
title: "Trackhop without a paid music service: new music every week on free accounts"
description: No Spotify Premium, no YouTube Music Premium? Trackhop fills the free accounts of YouTube Music, Spotify, Deezer and SoundCloud, or your own server, with new releases, charts, radio, setlists and AI playlists that update by themselves. Free and open source.
---

# Trackhop without a paid music service

You don't need a subscription to get something out of Trackhop. The free accounts of YouTube Music,
Spotify, Deezer and SoundCloud all keep playlists, and Trackhop can fill them for you, and keep them
fresh, from sources that cost nothing either.

Trackhop does not play or download music: you listen in the service's own app, with its ads and the
limits of its free plan. What Trackhop does is put the right songs there and keep them up to date.

## Pick where you listen

- **YouTube Music** (free account): playlists and liked songs, with ads. In the GitHub/F-Droid build,
  Desktop and Docker you sign in with your Google account; in the store builds through the official
  Google route with your own key ([how to make it](youtube-google-key)).
- **Spotify Free**: playlists and liked songs, with ads. Writing to Spotify through its official
  route needs a developer key, which Spotify gives to Premium accounts only; the quick sign-in of the
  GitHub/F-Droid build, Desktop and Docker works with a free account.
- **Deezer Free** and **SoundCloud**: playlists and likes, through the quick sign-in.
- **Your own server** (Navidrome, Jellyfin, Emby, Plex) with music you own: no ads, no account
  anywhere else.

You can pick more than one: the same playlist can go to each of them.

## Fill it with music that changes

Each of these is a sync like any other: choose the source, choose your free account as the
destination, and pick how often it runs.

- **New releases** of the artists you like: the albums, EPs and singles of a playlist's artists,
  added every week. [How](new-releases)
- **Charts**: a country's Top 100 from Apple Music or Deezer, refreshed as it changes, no account
  needed for the source.
- **A radio station**: what a station plays, collected into a playlist. [How](radio)
- **What you Shazam**, as a playlist. [How](shazam)
- **Setlists**: what an artist played live, as a playlist. [How](setlists)
- **An AI playlist** from a few words, with your own AI key or an AI on your phone. [How](cards)
- **Your Wrapped**, from Last.fm or ListenBrainz if you scrobble there. [How](wrapped-anytime)

## Keep it when you change service

Moving from one free service to another, or to a paid one later, is the reason Trackhop exists:
[transfer your playlists](getting-started) and keep them in step. A
[backup](backup-spotify-playlists) keeps a copy of every playlist as files, whatever happens to an
account.

## What Trackhop will not do

It will not download songs from YouTube or any streaming service: that breaks the services' terms
and, in most countries, copyright law, and it is not allowed on Google Play. For music you own,
point Trackhop at your own server, and use [*Missing on my server*](self-hosted-playlist-sync#missing-on-my-server) to see what your library lacks.
