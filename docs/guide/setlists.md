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
found, with **Start now** to fix the songs not found. It is a one-off transfer, not a sync: it
doesn't appear in Syncs. Its notification opens the songs to fix, and **Tools › Recent transfers**
keeps the last 20 with their summary and review for a week (or until the review is done); deleting
one there leaves the playlist as it is. The setlist itself is kept as a File playlist.

In the app this works with or without a key; on Docker and Desktop the setlist opens the editor
of a new sync, as for any sync.

## setlist.fm links

A setlist.fm link shared to Trackhop (or pasted in Services › File › Paste a list) also becomes a
playlist. With a setlist.fm key (below) it is read through setlist.fm's API. Without one, Trackhop
never reads the page in the background: the GitHub and F-Droid builds open it on setlist.fm's own
site in the app (below); the Google Play and AppGallery builds do the same through a connector
plugin that offers it, and otherwise ask for a key.

## Browse setlist.fm (GitHub and F-Droid builds, or a connector plugin)

Without a key, the GitHub and F-Droid builds of the app also offer **Browse setlist.fm** on the
Setlists page (and on each artist's card): it opens **setlist.fm's own website** inside the app, on
its search for the artist, like a browser. You browse it yourself and open a concert's page; while
a setlist is on screen, a bar at the bottom offers **Make a playlist** and **Add to a playlist**.
Trackhop then reads the songs from the page you are looking at (that page only, nothing else is
asked of setlist.fm) and brings you back to the Setlists page with the concert ready: the same
choices as above, where only the songs not already in the playlist are added, with the review of
the songs not found. Nothing happens in the background and nothing is read for you: you browse,
you choose the concert. The Google Play and AppGallery builds offer the same when a connector plugin
that browses setlist.fm is installed (see [Which Trackhop?](versions)): the site then opens in the
plugin's screen, which hands the concert you chose back to the Setlists page. If the plugin is too
old for it, Trackhop says to update it. Without a plugin, setlist.fm needs your key there.

## Without a key: MusicBrainz

Without a setlist.fm key, the app shows the setlists that [MusicBrainz](https://musicbrainz.org/)'s
concert pages carry: free, no account, no key. MusicBrainz has them for far fewer concerts (mostly
big tours and festivals), so many artists will show none: the setlist.fm key below shows many more.
Each artist takes two or three lookups (MusicBrainz answers once a second) and is kept for a week.
Trackhop never reads setlist.fm's own web pages for this: setlist.fm's terms don't allow programs
to read the site, only its API with a key (in the GitHub and F-Droid builds, or with a connector
plugin, you can browse the site yourself, see **Browse setlist.fm** above). Docker and Desktop still
need the key.

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

It takes a couple of minutes. The key dialog in the app (and Settings › setlist.fm key on Docker
and Desktop) walks you through the same steps.

1. **Make a free setlist.fm account** on [setlist.fm's sign-up page](https://www.setlist.fm/signup)
   (**Create an account** in the app). You can sign in with Google or Facebook, but setlist.fm
   still asks you to finish registering an account there.
2. **Signed in, open [setlist.fm's API page](https://www.setlist.fm/settings/api)** (**Get a key**
   in the app) and create a new **application**.
3. **Name it** as you like (*Trackhop* is fine) and give it a **description of at least 30
   characters**; you can copy this one (the app has a **Copy** button):
   `Personal use: setlists to playlists in Trackhop`
4. **setlist.fm shows your key** right away: copy it, paste it in Trackhop and tap **Save**.

If you saved a wrong key, Trackhop says "setlist.fm refused the key": paste the right one and tap
**Find setlists** again.

## Where the setlists come from

With a key every setlist is setlist.fm's, written by its community. Trackhop shows "Setlists from
setlist.fm" with a link, and each setlist links to its page there ("On setlist.fm"), as setlist.fm's
API terms ask. Without a key they are MusicBrainz's, written by its community too: "Setlists from
MusicBrainz", and each one links to its concert page there.

## What is sent

The artist names of the playlist you pick go to setlist.fm (api.setlist.fm) with a key, or to
MusicBrainz (musicbrainz.org) without one, only when you tap **Find setlists**; a shared link sends its setlist's id.
**Browse setlist.fm** opens www.setlist.fm in the app like a browser: what it sees is what you
browse there. Nothing else: no account, no location, no
playlist. See the [privacy policy](../privacy-policy).
