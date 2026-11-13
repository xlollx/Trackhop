# Trackhop 1.18: draft release notes (not shipped)

A draft for the day 1.18 is released: it is not linked from anywhere and not published on the site
(`_config.yml` leaves it out). English only; the translations come when the notes are final. These
features are in the code already, hidden in 1.17 behind the release switch (`Features.v2` in the
app's core and on the server): a 1.18 build is made with `-PV2=true`, the server turns them on with
`TRACKHOP_PREVIEW_2=1` (and `Features.DEFAULT = true` from 1.18).

## Store notes (F-Droid changelog and Google Play, at most 500 characters each)

**F-Droid / GitHub:**

> Trackhop 1.18. Discover from your music: your Wrapped from what you really play, a poster of the songs you play most, new releases, concerts, setlists, records and merch of your artists, alerts for the artists you follow. Shared playlists, one playlist for a group, each on their own service. Hand a sync to your Trackhop server. Podcasts with OPML. Playlist covers on more services. TIDAL liked tracks, Apple Music favorites and Replay, ListenBrainz playlists made for you, Amazon Music purchases.

**Google Play** (never the Bridge, nothing only the web connectors do):

> Trackhop 1.18. Discover from your music: your Wrapped from what you really play, a poster of the songs you play most, new releases, concerts, setlists, records and merch of your artists, and alerts for the artists you follow. Shared playlists: one playlist for a group, each on their own service. Hand a sync to your Trackhop server. Podcasts with OPML. Playlist covers on more services. TIDAL liked tracks, Apple Music favorites and Replay, ListenBrainz playlists made for you.

## In-app What's new (highlights)

Texts written earlier for these features are still in the app's strings in twelve languages
(`whats_new_118_*`, and `whats_new_119_*` for shared playlists and the server) and can be reused
for the 1.18 entry.

- **Your Wrapped, from what you really play**: Last.fm, ListenBrainz, Spotify, Deezer, TIDAL, YouTube Music, Apple Music's recent plays and Replay, or your own server's play counts, for the month, the half year or the year.
- **A poster of the songs you play most**: the poster lists the songs of the playlist you play most, and puts its drawn cover on your playlist where the service takes covers.
- **New releases of your artists**: the new albums, EPs and singles of a playlist's artists, and what is coming soon; as a playlist on any service, or a sync that adds them every week.
- **Concerts and setlists**: upcoming dates from Ticketmaster, MusicBrainz, the artists' own sites and Spotify; what they played live, from setlist.fm, as a playlist.
- **Records and merch**: CDs, vinyl and cassettes on Discogs, and the merch in the artists' own shops (Shopify, WooCommerce, Big Cartel, Squarespace, Bandcamp). Plain links: you buy on their sites.
- **Followed artists**: tap the bell next to an artist, then hear once a day about their new releases (Spotify's What's new too), concerts near you, merch and records.
- **Shared playlists**: one playlist for a group, each on their own service. Curators change it, the others follow it as an exact copy or keeping their own additions. Invite with a QR code or a link; the group meets in a shared folder, an end-to-end encrypted ntfy channel or your own Trackhop server.
- **Your server, from the phone**: hand a sync over to your Trackhop server and bring it back; Tools › My server runs and reviews the server's syncs; Coordinate with my server lets phone and server take turns on a playlist.
- **Podcasts**: YouTube Music's followed podcasts as a source; shows matched by their feed; your followed podcasts exported as OPML for any podcast app.
- **Covers everywhere**: a sync copies the playlist's cover to Spotify (web), YouTube Music, YouTube, TIDAL, SoundCloud, Jellyfin, Emby and Plex too.
- **More of your library**: TIDAL liked tracks, Apple Music favorites and recent plays, ListenBrainz's playlists made for you, Amazon Music purchases; TIDAL and YouTube keep the source's order.
- **Encrypted full backup**: Backup & restore can include your sign-ins, client secrets, web sign-ins and scheduled syncs in the `.trackhop` file, locked with a password of yours (AES-256-GCM); restoring asks whether this device replaces the other one, so its schedules stay on. The same file moves between the app, Docker and Desktop.
- **Synced lyrics**: Tools › Synced lyrics lists the songs on your Navidrome, Jellyfin, Emby, Plex or Lyrion server without lyrics and looks them up on LRCLIB; on Jellyfin it adds them, after you see what was found. Lyrics already there are never changed.

## Desktop and Docker (server CHANGELOG)

The lines taken out of the 1.17 (server 1.10) section, to put back with the server's 1.18:

- **New releases**: Tools › New releases lists the new albums, EPs and singles of a playlist's artists from Deezer and MusicBrainz, and what is announced; make them a playlist on any service, or a weekly *New releases* sync that keeps every track. `GET /api/v1/releases`, `trackhop releases` and the MCP tool `find_new_releases`; a new notification event tells when a weekly New releases sync found new music.
- **Concerts**: Tools › Concerts lists the upcoming dates of a playlist's artists in a country, from Ticketmaster and MusicBrainz, with ticket-site searches for artists without dates; your own Ticketmaster key in Settings or `TICKETMASTER_API_KEY`. `GET /api/v1/concerts`, `trackhop concerts` and the MCP tool `find_concerts`.
- **Setlists**: Tools › Setlists shows what a playlist's artists played live, from setlist.fm, and saves a setlist as a File playlist to send to any service. It needs your own free setlist.fm key, in Settings or `SETLISTFM_API_KEY`. `GET /api/v1/setlists`, `trackhop setlists` and the MCP tool `find_setlists`.
- **Records and merch**: Tools › Records and merch finds a playlist's artists' CDs, vinyl and cassettes on Discogs, the official sites and shops MusicBrainz links them to, and searches on Bandcamp, Discogs, eBay and the web; plain links. With your own Discogs token (Settings), the copies for sale and the lowest price. `GET /api/v1/records`, `trackhop records` and the MCP tool `find_records`.
- **Wrapped, playlist poster and followed artists** (with the app's next release): Tools › Wrapped makes your Wrapped from what you really played (Last.fm, ListenBrainz, your own server's play counts, Spotify, Deezer, TIDAL, YouTube Music) with its card as a PNG; Tools › Playlist poster lists the songs you play most in the cover's colours, to print; Tools › Followed artists tells your notification channels of your artists' new releases, concerts, merch and records, checked every day. Concerts also reads Spotify's tour dates where Spotify (web) is connected. In the API, the `trackhop` command and MCP.
- **Shared playlists' mailboxes**: a group sharing a playlist can meet on your server. The app makes the group's mailbox with your API token and gets the group's key, shown once; that key only reads and writes the signed items of its own group, nothing else on the server, and a new key ends the old one. `GET`/`POST /api/v1/groups`, `GET`/`DELETE /api/v1/groups/{group}`, `POST /api/v1/groups/{group}/key` and `GET`/`POST /api/v1/groups/{group}/items`.
- **Hand-over from the app**: a phone can hand a sync over to your server and bring it back. `POST /api/v1/syncs` takes an `id` of your own, which makes the call safe to repeat: sent again, it answers the sync made the first time.
- **Review choices shared with the app**: `GET`/`POST /api/v1/syncs/{sync}/decisions` give and take in the review's choices (ignored, matched by hand, kept), each with its time; for each track the newest choice wins on both sides.
- **Podcasts**: YouTube Music's followed podcasts as a source; followed podcasts exported as OPML, with their feeds looked up.
- **Playlist covers on more services**: Spotify (web), YouTube Music, TIDAL, YouTube, Jellyfin, Emby and Plex take the source's cover.
- **More of your library**: TIDAL liked tracks, Apple Music favorites and recent plays, ListenBrainz's playlists made for you.
- **Encrypted full backup**: Backup & restore › Your set-up can save the file with the sign-ins (`tokens.json`), client secrets and pasted web cookies, locked with a password (AES-256-GCM, PBKDF2 with 600,000 rounds); restoring one asks its password and whether this server replaces the other device. `POST /api/v1/config/encrypted`, `POST /api/v1/config` with the `X-Backup-Password` header, `trackhop config --encrypted` and `trackhop config import <file> --replaces-device --replace-sign-ins`. A phone's Bridge sessions are not used on a server, and the other way round.
- **Synced lyrics from your server**: Tools › Synced lyrics lists the songs of a connected Navidrome (and other Subsonic servers), Jellyfin, Emby, Plex or Lyrion server without lyrics, looks them up on LRCLIB and shows what it found before anything is written. Jellyfin takes them through its API; for Navidrome, Emby and Plex they are saved as `.lrc` (synced) or `.txt` (plain) next to the audio file, once the server's music folder is mapped to a folder here (mounted read-write on Docker). Nothing is ever replaced. Mopidy has no lyrics. `GET /api/v1/tools/nolyrics`, `POST /api/v1/tools/nolyrics/lookup` and `/save`, `trackhop nolyrics` and the MCP tool `find_songs_without_lyrics`.

## On the day

- Build with `-PV2=true` (the app) and set `Features.DEFAULT = true` (server/src/main/kotlin/com/xlollx/songport/server/Features.kt).
- Notes for the new versionCode in `fastlane/metadata/android/*/changelogs/` and `play/whatsnew/<code>/`, a "1.18" entry in the app's What's new, the server CHANGELOG.
- The README gallery: take "Upcoming" off the 1.18 slides.
