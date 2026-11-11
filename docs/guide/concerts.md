---
title: Concerts of the artists in your playlist, from Spotify, Apple Music, YouTube Music or any service
description: 'Pick a playlist and a country: Trackhop lists the upcoming concerts of its artists, with date, city, venue and a link to the tickets, from Ticketmaster and MusicBrainz. Free, no location permission.'
---

# Concerts of the artists in your playlist

Pick one of your playlists and a country: **Trackhop** looks up the upcoming concerts of the
artists in it and lists them by artist, with the **date, city and venue** and a link to the
event. It works from any service you use (Spotify, Apple Music, YouTube Music, Deezer, TIDAL,
your own Navidrome or Jellyfin…) and from a pasted playlist link.

In the app: **Discover › Concerts**. On Docker and Desktop: **Tools › Concerts**, the API
(`GET /api/v1/concerts`), the command line (`trackhop concerts`) and the MCP tool `find_concerts`.

## Where the dates come from

- **Ticketmaster** (Discovery API): dates with a link to the event's page on Ticketmaster, where
  the tickets are. Marked "Tickets on Ticketmaster".
- **MusicBrainz** events: a free, community-made database. Its dates are marked "MusicBrainz" and
  link to the event's page there. A date both know is shown once, Ticketmaster's.
- **Vivaticket** (unofficial, Italy only): in the GitHub and F-Droid builds and on Docker and
  Desktop (not in the Google Play, AppGallery or Microsoft Store builds), when the country is Italy,
  Trackhop also reads the dates on vivaticket.com, the way its own web pages do: no account, one
  request a second, each answer kept a day. They are marked "Tickets on Vivaticket (unofficial)".
  It is not an API offered to apps: **it may stop working at any time** when the site changes.
  Then Trackhop leaves it alone for an hour and shows the other sources' dates as usual.

The same date (same day, same city) from several sources is shown once, with a link for each ticket
site that sells it: "Tickets on Ticketmaster", "Tickets on Vivaticket (unofficial)".

No source lists every concert. **In Italy, concerts sold only on TicketOne are missing**: TicketOne's
site answers browsers only, and the search behind it is closed to apps by the site's rules
(robots.txt), so Trackhop does not read it. For each artist with no date found, Trackhop shows
search buttons for the country's ticket sites (in Italy TicketOne and Vivaticket; Eventim in
Germany, oeticket in Austria, Ticketcorner in Switzerland) and for the artist's official site. They
just open the search in your browser.

## The country

The app starts from the country of your mobile network (or your SIM, or your language). It never
asks for your location. Change it with the **Country** button.

## The Ticketmaster key

Ticketmaster answers only apps with a key, and each key has a daily limit (about 5,000 lookups,
5 a second).

- **The app from Google Play** comes with a key shared by everyone who uses it. To spare it,
  Trackhop looks up only the 40 artists the playlist holds most, only when you tap **Find
  concerts** (never in the background), and keeps each answer for a day. If the day's lookups run
  out you'll see "Today's Ticketmaster limit is reached": try again tomorrow, or add your own key.
- **Your own key** (free) lifts this: up to 150 artists, and a daily limit of your own. Make an app
  on [Ticketmaster's developer site](https://developer.ticketmaster.com/) and paste its
  **Consumer Key** in the app (Settings › Advanced › Service keys › Ticketmaster key, or the link on the
  Concerts page). Only the Consumer Key: never paste the Consumer Secret.
- **The GitHub and F-Droid builds** carry no key of ours: with no key, Concerts shows MusicBrainz's
  dates only, until you add yours.
- **Docker and Desktop**: put your key in **Settings › Ticketmaster key** on the page, or give the
  container `TICKETMASTER_API_KEY`. Each person on a shared server can save their own.

## What is sent

The artist names of the playlist you pick and the country go to Ticketmaster
(app.ticketmaster.com) and MusicBrainz (musicbrainz.org), and, in Italy where Vivaticket is read,
the artist names to Vivaticket (apigatewayb2cstore.vivaticket.com), only when you tap **Find concerts**.
Nothing else: no account, no location, no playlist. See the [privacy policy](../privacy-policy).
