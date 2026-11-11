---
title: Records and merch of the artists in your playlist, from Spotify, Apple Music, YouTube Music or any service
description: 'Pick a playlist: Trackhop lists the CDs, vinyl and cassettes of its artists from Discogs, their official sites and shops, and searches on Bandcamp, Discogs and other shops. Plain links, free, no account.'
---

# Records and merch of the artists in your playlist

Pick one of your playlists: **Trackhop** lists, for each of its artists, their records on **CD,
vinyl and cassette**, their **official site and shop**, and buttons that search for them on
**Bandcamp, Discogs, eBay** and the web. It works from any service you use (Spotify, Apple Music,
YouTube Music, Deezer, TIDAL, your own Navidrome or Jellyfin…) and from a pasted playlist link.

In the app: **Discover › Records and merch**. On Docker and Desktop: **Tools › Records and merch**,
the API (`GET /api/v1/records`), the command line (`trackhop records`) and the MCP tool
`find_records`.

Trackhop sells nothing and earns nothing: every button is a **plain link** (no affiliate tag), you
buy on the shop's own site. It only shows physical goods and the artists' own pages.

## Where it comes from

- **Discogs**, the community record database: the records of each artist, the most owned first,
  one line per record with the formats it came out on and its first year. Bootlegs ("Unofficial
  Release") and digital files are left out. Each line opens the record on Discogs. Data provided by
  Discogs.
- **MusicBrainz**, the free music database: the pages it links to the artist, shown as buttons:
  the **official site**, the **official shop** ("purchase for mail-order"), **Bandcamp**, download
  stores and social networks. Trackhop never opens or reads those pages.
- **Shop searches**: Bandcamp, Discogs, eBay and a web search for the artist's store and merch.
  They just open the search in your browser.

The same name can belong to more than one artist: a record from a namesake may show up.

## The Discogs token (optional)

Discogs answers without any key, 25 lookups a minute: Trackhop looks up the 25 artists the playlist
holds most, only when you tap **Find records**, and keeps Discogs' answers for six hours (Discogs
asks apps not to show anything older than that). MusicBrainz's links are kept a week.

With **your own personal access token** (free) Discogs allows 60 lookups a minute: Trackhop looks
up up to 50 artists, and the first records of each artist also say **how many copies are for sale
on the Discogs marketplace, and from what price** (in your language's currency when Discogs has it,
else euros). Make it on Discogs under **Settings › Developers › Generate new token**, then paste it in
the app (**Settings › Advanced › Service keys › Discogs token**, or the link on the page) or on Docker and
Desktop in **Settings › Discogs token**. Trackhop only reads with it; on the phone it is kept
encrypted with your sign-ins, and it is never part of an exported set-up.

To look up other artists, or fewer, tick them under **Artists** (or type a name): then every one you
choose is looked up.

### Getting the token, step by step

1. Log in to [Discogs](https://www.discogs.com/) (a free account is enough).
2. Open [Settings › Developers](https://www.discogs.com/settings/developers) (the **Get a token**
   button in the app opens it).
3. Tap **Generate new token** and copy it.
4. Paste it in Trackhop and tap **Save**.

If Discogs asks to slow down, you'll see "Discogs asked to slow down": the artists after that keep
their official pages and searches; try again in a minute.

## What is sent

The artist names of the playlist you pick go to Discogs (api.discogs.com) and MusicBrainz
(musicbrainz.org), only when you tap **Find records**; with your token, the token goes to Discogs
too. Nothing else: no account, no location, no playlist. See the [privacy policy](../privacy-policy).

This application uses Discogs' API but is not affiliated with, sponsored or endorsed by Discogs.
"Discogs" is a trademark of Zink Media, LLC.
