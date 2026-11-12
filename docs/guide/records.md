---
title: Records and merch of the artists in your playlist, from Spotify, Apple Music, YouTube Music or any service
description: 'Pick a playlist: Trackhop lists the CDs, vinyl and cassettes of its artists from Discogs, the merch in their own shops (t-shirts, hoodies, posters…) with pictures and prices, their official sites, and searches on Bandcamp, Discogs and other shops. Plain links, free, no account.'
---

# Records and merch of the artists in your playlist

Pick one of your playlists: **Trackhop** lists, for each of its artists, their records on **CD,
vinyl and cassette** (from Discogs, and the ones on sale in their own shops), the **merch** on sale
in their own shops (t-shirts, hoodies, posters, bundles…) with a picture and the price, their **official site and shop**, and buttons that search for them on
**Bandcamp, Discogs, eBay** and the web. It works from any service you use (Spotify, Apple Music,
YouTube Music, Deezer, TIDAL, your own Navidrome or Jellyfin…) and from a pasted playlist link.

In the app: **Discover › Records and merch**. On Docker and Desktop: **Tools › Records and merch**,
the API (`GET /api/v1/records`), the command line (`trackhop records`) and the MCP tool
`find_records`.

Trackhop sells nothing and earns nothing: every button is a **plain link** (no affiliate tag), you
buy on the shop's own site. It only shows physical goods and the artists' own pages.

At the top of the page, **All / CD & vinyl / Merch** chooses what is shown and what is looked up
(Trackhop remembers it): **CD & vinyl** shows the records, from Discogs and from the artists' own
shops; **Merch** shows only the other goods of those shops (clothing, pins, posters, bags…), and
Discogs is not asked. Pick the other one later and only what is missing is looked up. With CD &
vinyl, the **Vinyl / CD / Cassette** chips narrow the records to those formats, the shops' ones too.

## Where it comes from

- **Discogs**, the community record database: the records of each artist, the most owned first,
  one line per record with the formats it came out on and its first year. Bootlegs ("Unofficial
  Release") and digital files are left out. Each line opens the record on Discogs. Data provided by
  Discogs.
- **MusicBrainz**, the free music database: the pages it links to the artist, shown as buttons:
  the **official site**, the **official shop** ("purchase for mail-order"), **Bandcamp**, download
  stores and social networks. Trackhop never opens or reads those pages.
- **The artist's own shops**, for the merch and the records sold there: among the pages MusicBrainz links to the artist,
  - an **official shop on Shopify** (the mail-order shop, or the official site when it is the shop):
    Trackhop reads its public product list (`/products.json`, the first 30 products, and the shop's
    currency from `/meta.json`) and shows the products on sale, digital downloads left out, with
    their lowest price. A label's shop shows only the products that name the artist. Big retailers
    (Amazon, eBay, Target…) are never asked.
  - the artist's **Bandcamp**: the items of its merch page (`/merch`), sold-out ones left out.
  - a shop on **another platform**, on the official site or one it links to: Trackhop first tells the
    platform from the page itself, then reads only that platform's public catalogue (WooCommerce's
    Store API, Big Cartel's product list, a Squarespace shop page where its robots.txt allows it),
    else the product data the shop page and a few of its product pages carry for search engines.
    These are labelled with the shop's domain.

  When MusicBrainz lists no official site or shop for an artist (many artists who sell only on their
  own site), Trackhop asks **Wikidata** for the artist's official site and Bandcamp, taking the entry
  only when it is surely that artist (their MusicBrainz or Spotify id, else the only musician or band
  of exactly that name); with the Spotify (web) connector connected, the artist's page on Spotify adds
  its links and merch shelf. Then the shops are found on that site as above.

  The records among them (a vinyl, LP, 7", CD or cassette, by the shop's product type or else its
  name) are shown with the records, under **In their shops**, and the rest as merch: up to 12 of
  each per artist, each labelled **Official shop** or **Bandcamp**; a tap opens the product's page. Each shop's robots.txt is read first and honoured (a shop that closes those pages
  is skipped), Trackhop asks a shop at most once a second and keeps what it found for a day. For
  up to 15 artists (the default) the shops are read during the search, one artist at a time, so the
  page is complete when it ends; when you pick more artists, each artist's shops are read as its
  card comes into view. The
  pictures follow **Settings › Images in Discover** (and its Wi-Fi only option).
- **Shop searches**: Bandcamp, Discogs, eBay and a web search for the artist's store and merch.
  They just open the search in your browser.

The same name can belong to more than one artist. Trackhop picks the playlist's own: the artist
MusicBrainz links to on Discogs, else the one whose records share titles with the playlist's albums
and songs. When nothing tells them apart, the card says another artist may share the name.

A search can be stopped with **Stop**: the artists already looked up stay on the page.

## The Discogs token (optional)

Discogs answers without any key, 25 lookups a minute: Trackhop looks up the 15 artists the playlist
holds most (or the ones you choose under **Artists**), only when you tap **Find records**, and keeps Discogs' answers for six hours (Discogs
asks apps not to show anything older than that). MusicBrainz's links are kept a week.

With **your own personal access token** (free) Discogs allows 60 lookups a minute: lookups are quicker,
and the first records of each artist also say **how many copies are for sale
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
too. For their records and merch, the artist's own shops that MusicBrainz lists (their Shopify shop or official
site, their `name.bandcamp.com`, a shop on another platform their site links to) are asked for their
public product list; nothing of yours is sent to them, and pictures load from cdn.shopify.com,
bcbits.com and the shops' own servers. For an artist MusicBrainz lists no site for, the artist's
name goes to Wikidata (www.wikidata.org). Nothing else: no account, no
location, no playlist. See the [privacy policy](../privacy-policy).

This application uses Discogs' API but is not affiliated with, sponsored or endorsed by Discogs.
"Discogs" is a trademark of Zink Media, LLC.
