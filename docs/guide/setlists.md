---
title: Setlists of the artists in your playlist, as a playlist on Spotify, Apple Music, YouTube Music or any service
description: 'Pick a playlist: Trackhop shows the latest setlists of its artists, from setlist.fm or MusicBrainz. Pick a concert and make it a playlist on any service, encores included. Free, no key needed.'
---

# Setlists of the artists in your playlist

Pick one of your playlists: **Trackhop** shows the latest setlists of the artists in it, from
[setlist.fm](https://www.setlist.fm/) with your free key, or from [MusicBrainz](https://musicbrainz.org/)
without one: up to five recent concerts each, with the date, venue,
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

Under the concert, **Make a playlist** asks only where: pick the service and Trackhop creates the
playlist there. **Add to a playlist** asks for one of your playlists instead and adds only the songs
not already in it. Either way you see what was added, what was already there and what was not
found, with **Start now** to fix the songs not found. The setlist is kept as a File playlist and
the sync stays in Syncs, so you can run it again.

In the app this works with or without a key; on Docker and Desktop the setlist opens the editor
of a new sync, as for any sync.

## setlist.fm links

A setlist.fm link shared to Trackhop (or pasted in Services › File › Paste a list) also becomes a
playlist. With a setlist.fm key (below) it is read through setlist.fm's API; without one Trackhop
tries the public page, which setlist.fm sometimes protects with a browser check: then add a key.

## Without a key: MusicBrainz

Without a setlist.fm key, the app shows the setlists that [MusicBrainz](https://musicbrainz.org/)'s
concert pages carry: free, no account, no key. MusicBrainz has them for far fewer concerts (mostly
big tours and festivals), so many artists will show none: the setlist.fm key below shows many more.
Each artist takes two or three lookups (MusicBrainz answers once a second) and is kept for a week.
Trackhop never reads setlist.fm's own web pages for this: setlist.fm's terms don't allow programs
to read the site, only its API with a key. Docker and Desktop still need the key.

## The setlist.fm key

setlist.fm answers only apps with a key, free for non-commercial use, and each key has a limit:
2 lookups a second and 1,440 a day.

- **Every build of the app** uses your own key for setlist.fm (without one it shows MusicBrainz's): setlist.fm's API terms don't allow a key shared by
  everyone, so Trackhop carries none. It's free: with a setlist.fm account, ask for one on
  [setlist.fm's API page](https://www.setlist.fm/settings/api) and paste it in the app (Settings ›
  Advanced › Service keys › setlist.fm key, or the button on the Setlists page). Trackhop keeps it
  encrypted with your sign-ins, looks up the 15 artists the playlist holds most (or the ones you
  choose under **Artists**), only when you tap
  **Find setlists** (never in the background), and keeps each artist's setlists for a week. If the
  day's lookups run out you'll see "Today's setlist.fm limit is reached": try again tomorrow. It
  only reads public setlists: nothing about you.
- **Docker and Desktop**: put your key in **Settings › setlist.fm key** on the page, or give the
  container `SETLISTFM_API_KEY`. Each person on a shared server can save their own.

### Getting the key, step by step

1. Make a free account on [setlist.fm](https://www.setlist.fm/), or log in.
2. Open [Settings › API](https://www.setlist.fm/settings/api) (the **Get a key** button in the app opens it).
3. Apply for a key: application name *Trackhop*, description *Personal use: setlists of my
   playlists' artists*, non-commercial.
4. Copy the key shown there, paste it in Trackhop and tap **Save**.

## Where the setlists come from

With a key every setlist is setlist.fm's, written by its community. Trackhop shows "Setlists from
setlist.fm" with a link, and each setlist links to its page there ("On setlist.fm"), as setlist.fm's
API terms ask. Without a key they are MusicBrainz's, written by its community too: "Setlists from
MusicBrainz", and each one links to its concert page there.

## What is sent

The artist names of the playlist you pick go to setlist.fm (api.setlist.fm) with a key, or to
MusicBrainz (musicbrainz.org) without one, only when you tap **Find setlists**; a shared link sends its setlist's id. Nothing else: no account, no location, no
playlist. See the [privacy policy](../privacy-policy).
