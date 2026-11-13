# Cards: "how compatible are we?" and the playlist poster

Two cards of the Discover tab that end in an image to share, in the story format (1080×1920) or the
square one (1080×1080). Nothing is created or changed on any service: they only read.

Discover holds a few more, all computed on the phone:

- **Your Wrapped, whenever you want**: top artist, top decade, songs in your playlists, services and a
  playful "musical age" (the median release year of your songs, minus 17), on a story card.
- **Universal link**: a playlist or a song as a `trackhop.org/open#…` link. The songs travel in the
  part after `#`, which browsers never send to a server; the page lists them with buttons that search
  each one on Spotify, Apple Music, YouTube Music, Deezer, TIDAL or Amazon Music. A long playlist is cut
  to what a chat app lets through, and the page says so.
- **Playlist roast**: with the AI you set up for the Playlist generator, a short affectionate roast of a
  playlist. Only a summary goes to your AI: name, counts, top artists, decades and twenty titles.
- **Vanished songs**: songs in an older backup copy of a playlist and missing from the newest one, and
  songs a Mirror sync removed, each with "Search on…".
- **Two subscriptions?**: how much the liked songs and playlists of two of your services overlap, and
  what both cost at the prices you type (kept on the phone). It never says which one to keep.

## How compatible are we?

Two playlists in, a score out. Each side can be:

- a **public link** pasted in (Spotify, Apple Music, YouTube/YouTube Music, Deezer, TIDAL, and
  SoundCloud in the GitHub/F-Droid build or with the Trackhop Bridge plugin; short share links too), or
- one of **your own playlists** on a connected service, liked songs included.

Sharing **two playlist links at once** to Trackhop (select both messages in a chat, or paste two
links in one share) opens the comparison directly.

The two lists are matched the way a sync matches them: an identical ISRC is the same recording,
otherwise title, artist and duration are compared, so "Master Of Puppets (Remastered)" on one
side counts as "Master of Puppets" on the other.

**The score**, 0 to 100, is:

- 70% of it: the share of the *smaller* playlist that is also in the other one;
- 30% of it: the share of the smaller side's artists that appear on the other side.

The smaller side is the base on purpose: a 20-track playlist entirely contained in a 1,000-track
one scores 100, not 2. The result screen also lists the tracks in common, the shared artists, and
"for you" suggestions: the other side's tracks by artists you both listen to that you do not have.

The card shows the score, both names, up to five common tracks with their covers and the shared
artists. Covers come from the services or, when a track has none, from Deezer's public catalogue
(by ISRC, or by an exact enough title and artist); a track without a cover gets a coloured tile.

### Which links work without logging in

| Service | Public link readable without an account? |
|---|---|
| Deezer | Yes |
| Apple Music | Catalogue playlists (`pl.…`) yes, when the build has an Apple developer token; a user's library playlist needs Apple Music connected |
| Spotify, TIDAL, YouTube | No: their APIs need a token. Connect the service once; a public link of any other user then works |
| Amazon Music, Navidrome, Jellyfin, Plex | Own playlists only, from the connected account |

## Playlist poster

Pick a playlist (yours, or a public link) and get a card with the cover (or a 2×2 mosaic of the
first tracks' covers), the title, the owner and service, the track count and total duration, and a
**QR code** that opens the playlist on its service:

- Spotify, Apple Music, YouTube Music, Deezer, TIDAL, Amazon Music: the playlist's public page.
  Scanning it opens the service's app when installed. A private playlist opens only for its owner.
- Jellyfin: the playlist's page on your server; Navidrome/Subsonic and Plex: your server's address.
  Only reachable by people on the same network, or through your reverse proxy.

The QR code is generated on the phone (byte mode, error correction level M, up to 213 bytes), so
nothing is sent anywhere to make it. Services with no public page get a poster without a QR.

The mosaic takes four tracks from four different albums, so a playlist that opens with several
songs of one record does not show the same cover four times.

### A cover drawn by your AI

Tick **Cover drawn by your AI** to have the AI you set up for the Playlist generator draw the art
instead: OpenAI (gpt-image-1, or DALL·E 3 when the key has no access to it), Google Gemini
(gemini-2.5-flash-image) or an OpenAI-compatible server for which you entered an image model.
Claude, Groq and OpenRouter do not make images here, and the option says so.

It runs on your own key, so it may cost a few cents per picture. Only the playlist's title, its five
most frequent artists and its description are sent, with a request for square cover art without
lettering, logos or real people's faces. The picture is kept for the session: **Make the card again**
does not pay twice, **Another cover** asks for a new one, **Save cover** keeps it as a PNG.

## Sharing and saving

**Share** opens the system share sheet with the PNG; **Save image** asks where to put the file.
Cards carry a small "Trackhop · trackhop.org" line at the bottom: that is how people
who see one find the app.
