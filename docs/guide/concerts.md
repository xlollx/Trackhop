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

## The time range

The **When** button keeps the dates of the next month, 3, 6 or 12 months, or any date. Trackhop
remembers your choice.

## Only some artists

By default Trackhop looks up the 50 artists the playlist holds most. Open **Artists** to tick only the
ones you want (with a search box, in alphabetical order), or type the name of an artist who is not in
the playlist: fewer lookups, and quicker.

## Add to calendar

Each date has an **Add to calendar** button: it opens your calendar app on a new event with the
artist and venue, the start time in the concert's own time zone (a whole-day event when the time is
not known), the place and the ticket link. You save it there: Trackhop needs no calendar permission.

## The Ticketmaster key

Ticketmaster answers only apps with a key, and its licence does not let an app share one with its
users: **Ticketmaster's dates need your own free key**. Without one, Concerts shows MusicBrainz's
dates only.

### Getting the key, step by step

1. Open Ticketmaster's [Getting started](https://developer.ticketmaster.com/products-and-docs/apis/getting-started/)
   page (the **Get a key** button in the app opens it). You need a Ticketmaster **developer** account:
   it's not the account you buy tickets with.
2. Tap **Get your API key**, then register or log in to the developer portal.
3. In **My Apps**, open your app and copy its **Consumer Key**. Never the Consumer Secret.
4. Paste it in Trackhop (Settings › Advanced › Service keys › Ticketmaster key, or the link on the
   Concerts page) and tap **Save**.

Each key has a daily
limit (about 5,000 lookups): if it runs out you'll see "Today's Ticketmaster limit is reached", try
again tomorrow. Trackhop asks only when you tap **Find concerts** (never in the background) and keeps
each answer for a day.

On **Docker and Desktop**: put your key in **Settings › Ticketmaster key** on the page, or give the
container `TICKETMASTER_API_KEY` (the server's owner's own key, for everyone on that server). Each
person on a shared server can save their own.

## MusicBrainz's dates

MusicBrainz is made by its users, and few of them add concerts still to come: for most artists it has
none, or only the big festivals. Without a Ticketmaster key, expect few dates.

## What is sent

The artist names of the playlist you pick and the country go to Ticketmaster
(app.ticketmaster.com) and MusicBrainz (musicbrainz.org), and, in Italy where Vivaticket is read,
the artist names to Vivaticket (apigatewayb2cstore.vivaticket.com), only when you tap **Find concerts**.
Nothing else: no account, no location, no playlist. See the [privacy policy](../privacy-policy).
