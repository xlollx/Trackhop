
# Unplayable tracks

Every long-lived playlist collects a few greyed-out entries: a track pulled from the catalogue, one
the label stopped licensing in your country, one outside your plan, a YouTube video gone private.
The services show them and leave them there. Trackhop finds them and offers the same recording
under an id that plays.

## Where

Tools → pick the service → **Unplayable tracks**. Choose one playlist or all of yours (liked songs
included where the service has them) and tap **Check**.

## What it does

1. **Asks the service** which entries it can no longer play. Nothing is guessed from titles.
2. **Proposes a replacement** for each one, with the matching the sync uses: ISRC first, then
   title, artist and duration, with live, karaoke and sped-up versions penalised when the original
   is not one. A proposal the sync would accept on its own is pre-selected; a doubtful one waits
   for you. Where Spotify itself already plays another edition in the entry's place (its
   "relinking"), that edition is the proposal, and it is a sure thing.
3. **Lets you decide**, entry by entry: accept the proposal, open the entry and search another
   version (or paste its link), remove the entry only, or skip it.
4. **Applies everything in one go**, after a confirmation: each replacement is added, each dead
   entry removed, and where the service can move tracks (Spotify, YouTube Music, Deezer) the
   replacement takes the dead entry's spot. Nothing changes before you tap Apply.
5. **Can be undone**: what was removed and what was added is kept, and **Undo** puts the playlist
   back as it was.

## Which services

| Service | Says what it cannot play | Note |
|---|---|---|
| Spotify | Yes, with the reason (country, plan, explicit filter) | Both the official route and the web route |
| Deezer | Yes | |
| TIDAL | Yes | |
| Apple Music | Library playlists only | With a developer token, Apple's API cannot remove tracks: the playable version is added, the dead entry stays. The Apple ID sign-in replaces it. |
| YouTube | Private and deleted videos | |
| YouTube Music | Greyed-out entries | GitHub/F-Droid build |
| Amazon Music, Navidrome/Subsonic, Jellyfin, Plex | No | The tool says so instead of guessing |

## The weekly check

Settings → **Weekly check for unplayable tracks**. Once a week Trackhop looks through your
playlists and sends a notification when something can no longer be played. It never changes
anything by itself: the notification opens the tool, and you decide.

[Back to the guide](./)
