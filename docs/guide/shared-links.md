# Shared links

Someone sends you a Spotify link and you are on YouTube Music, or on your own Navidrome. Share
that link to Trackhop and the track lands in one of your playlists there, matched the way a sync
matches, and shown to you before it is added.

## Share a track

1. In the app the link came from (a messenger, a browser, the music app itself), tap **Share**
   and pick **Trackhop**. Spotify, Apple Music, YouTube Music, Amazon Music, Deezer and TIDAL
   track links are recognised, short share links too.
2. The **Add to…** sheet opens with the track at the top. If the link's own service is not
   connected, Trackhop reads the title from public pages, with no account involved.
3. Pick **where** it goes: every connected service is listed, the last one used first. Navidrome,
   Subsonic, Jellyfin, Emby and Plex are targets like the others.
4. Trackhop looks for the same recording there. When the service knows the very track (its own
   link, or one song.link maps), you see it as is; otherwise the best match with its score, and
   **Change** shows the other candidates with the usual search.
5. **Add to playlist…** opens the list of your playlists (and liked songs, where the service
   supports it). **Open in…** opens the matched track in that service's app or site;
   **Copy link** keeps the original address.

Playlist links keep working as before: shared to Trackhop, they become the source of a new sync.

## Music links open with Trackhop

Off by default. In **Settings › Offer Trackhop for music links**, turn it on and Android lists
Trackhop next to the service's app whenever a music link is tapped. Android still asks which app
to use, or keeps the one chosen in its own link settings (Settings › Apps › Default apps ›
Opening links); Trackhop never takes a link by itself. Turn the switch off and it disappears from
those choices again.

## Where the title comes from

For a link whose service is connected, from that service. Otherwise, in this order: a connected
service that song.link knows the track on; Deezer's public catalogue (Deezer links); Apple's
lookup (Apple Music links); the page's own title (Spotify, YouTube, TIDAL, Amazon). None of these
needs an account. When none answers, the sheet asks you to search by hand.

[Back to the guide](./)
