---
title: Setlists of the artists in your playlist, as a playlist on Spotify, Apple Music, YouTube Music or any service
description: 'Pick a playlist: Trackhop shows the latest setlist.fm setlists of its artists. Pick a concert and make it a playlist on any service, encores included. Free.'
---

# Setlists of the artists in your playlist

Pick one of your playlists: **Trackhop** shows the latest setlists of the artists in it, from
[setlist.fm](https://www.setlist.fm/): up to five recent concerts each, with the date, venue,
city, tour and the songs in the order they were played. Pick a concert and **make it a playlist**
on any service (Spotify, Apple Music, YouTube Music, Deezer, TIDAL, your own Navidrome or
Jellyfin…), to listen before the show or to relive it.

In the app: **Discover › Setlists**. On Docker and Desktop: **Tools › Setlists**, the API
(`GET /api/v1/setlists`), the command line (`trackhop setlists`) and the MCP tool `find_setlists`.

## What goes in the playlist

- The songs in the order played, **encores included** (they are shown under "Encore").
- A **cover** goes in under its original artist ("Feeling Good", Nina Simone), so the search finds
  the song you heard.
- Music played **from tape** (an intro, the walk-off song) is shown greyed and left out.

The playlist is first saved as a File playlist, then the editor of a new sync opens on it: pick
the service and a new or existing playlist there, as for any sync.

## setlist.fm links

A setlist.fm link shared to Trackhop (or pasted in Services › File › Paste a list) also becomes a
playlist. With a setlist.fm key (below) it is read through setlist.fm's API; without one Trackhop
tries the public page, which setlist.fm sometimes protects with a browser check: then add a key.

## The setlist.fm key

setlist.fm answers only apps with a key, free for non-commercial use, and each key has a limit:
2 lookups a second and 1,440 a day.

- **The app from Google Play** comes with a key shared by everyone who uses it. To spare it,
  Trackhop looks up only the 25 artists the playlist holds most, only when you tap **Find
  setlists** (never in the background), and keeps each artist's setlists for a week. If the day's
  lookups run out you'll see "Today's setlist.fm limit is reached": try again tomorrow, or add your
  own key.
- **Your own key** lifts this: up to 100 artists, and a daily limit of your own. With a setlist.fm
  account, ask for one on [setlist.fm's API page](https://www.setlist.fm/settings/api) and paste it
  in the app (Settings › Advanced › Service keys › setlist.fm key, or the button on the Setlists
  page). It only reads public setlists: nothing about you.
- **The GitHub and F-Droid builds** carry no key of ours: Setlists needs your own key there.
- **Docker and Desktop**: put your key in **Settings › setlist.fm key** on the page, or give the
  container `SETLISTFM_API_KEY`. Each person on a shared server can save their own.

## Where the setlists come from

Every setlist is setlist.fm's, written by its community. Trackhop shows "Setlists from setlist.fm"
with a link, and each setlist links to its page there ("On setlist.fm"), as setlist.fm's API terms
ask.

## What is sent

The artist names of the playlist you pick go to setlist.fm (api.setlist.fm), only when you tap
**Find setlists**; a shared link sends its setlist's id. Nothing else: no account, no location, no
playlist. See the [privacy policy](../privacy-policy).
