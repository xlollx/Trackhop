# Trackhop guide

Practical notes for people using Trackhop and for whoever maintains it. The full technical
description (architecture, matching, services and their limits) stays in the
[README](https://github.com/xlollx/Trackhop#readme); this guide is the "how do I…" side.

**Using the app**
- [Getting started](getting-started): install, connect a service, the first sync.
- [Which Trackhop?](versions): the GitHub/F-Droid and Play builds, connector plugins, the community.
- [Shared links](shared-links): a friend's Spotify link into your YouTube Music, Navidrome or Jellyfin playlist.
- [SoundCloud](soundcloud): a public SoundCloud set or likes page as the source of a sync, no account needed.
- [NetEase Cloud Music and QQ Music](netease-qq-music): a public 网易云音乐 or QQ音乐 playlist as the source of a sync, from its link, no Chinese account needed ([中文](netease-qq-music-zh)).
- [Troubleshooting](troubleshooting): the errors people actually meet, and what to do about each.
- [Is it safe?](security): every download checked on VirusTotal, the files' SHA-256 and how Trackhop is built.
- [Reporting a problem](reporting-a-problem): the diagnostic report, and what makes a report useful.
- [Cards](cards): "how compatible are we?" between two playlists, and the playlist poster with a QR code.
- [Unplayable tracks](unplayable-tracks): the greyed-out tracks in your playlists, and how Trackhop replaces them.
- [Trackhop Desktop (Windows, macOS, Linux)](desktop): the engine as a program on your computer, no Docker needed.
- [Trackhop on a server (Docker, beta)](docker): works for everyday use, still young. Scheduled syncs on a home server or a NAS, with a web page.
- [The trackhop command](cli): run syncs, read their results, add and remove tracks from a terminal or a script, with Desktop or Docker.

**On your computer or server**
- [Playlist sync for Windows](playlist-sync-windows), [for Mac](playlist-sync-mac), [for Linux](playlist-sync-linux): the desktop app, how to install it and what it does.
- [Self-hosted playlist sync](self-hosted-playlist-sync): your Navidrome, Jellyfin or Plex in step with your streaming service.
- [On a NAS or home server](playlist-sync-nas): Docker on Synology, Unraid, TrueNAS or a Raspberry Pi.

**Step by step**
- [Sync Spotify playlists to YouTube Music](spotify-to-youtube-music), and keep them in sync.
- [Transfer Apple Music playlists to Spotify](apple-music-to-spotify), no Mac or iPhone needed.
- [Move Spotify playlists to TIDAL](spotify-to-tidal), with exact ISRC matching.
- [Transfer Spotify playlists to Apple Music](spotify-to-apple-music), and keep adding new songs.
- [Transfer YouTube Music playlists to Spotify](youtube-music-to-spotify), on the phone or a computer.
- [Transfer Deezer playlists to Spotify](deezer-to-spotify), favourites included.
- [Transfer Amazon Music playlists to Spotify](amazon-music-to-spotify), or the other way round.
- [Transfer TIDAL playlists to Spotify](tidal-to-spotify), with exact ISRC matching.
- [Transfer Spotify playlists to Deezer](spotify-to-deezer) and [to Amazon Music](spotify-to-amazon-music).
- [Apple Music to YouTube Music, and back](apple-music-to-youtube-music).
- [Keep playlists in sync](sync-playlists) between any two services, on a schedule.
- [Free playlist sync](free-playlist-sync): no paid plan, no track limit, no account.
- [Transfer liked songs](transfer-liked-songs), favourites and saved albums.
- [Merge playlists and remove duplicates](merge-playlists-remove-duplicates).
- [Sync Spotify playlists to Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin), from your own library.
- [Export a playlist to CSV or M3U](export-playlist-csv), and back up all of them.
- [Your Shazams in a playlist](shazam): every song you Shazam, on any service.
- [Songs from a radio, in a playlist](radio): what a station plays in the hours you choose, on any service.

**Coming from another service**
- [A free Soundiiz alternative](soundiiz-alternative): the same moves, kept in sync, no account, no limits.
- [A free TuneMyMusic alternative](tunemymusic-alternative): transfers plus automatic sync, free and open source.
- [A free FreeYourMusic alternative](freeyourmusic-alternative): no paid plan, no track limit.
- [SongShift for Android?](songshift-android): SongShift is iPhone-only; Trackhop does it on Android.
- [Spotify's "Import your music" vs Trackhop](spotify-import-alternative): what the built-in tools do not do.

**En español**
- [Transferir playlists entre Spotify, YouTube Music, Apple Music y Deezer](transferir-playlists), gratis y sincronizadas.

**Em português**
- [Transferir playlists entre Spotify, YouTube Music, Apple Music e Deezer](transferir-playlist), grátis e sincronizadas.

**In italiano**
- [Trasferire playlist tra Spotify, YouTube Music, Apple Music e Amazon Music](trasferire-playlist), gratis e sincronizzate.
- [Alternativa gratuita a Soundiiz e TuneMyMusic](alternativa-soundiiz): niente account, niente limiti.
- [Da Spotify ad Apple Music](trasferire-playlist-spotify-apple-music) e [da Spotify a YouTube Music](trasferire-playlist-spotify-youtube-music), gratis.

**Maintaining it**
- [Release process](release-process): version, changelogs, what CI does, F-Droid and Obtainium.
- [Amazon Music capture](amazon-music-capture): what to do when Amazon changes its web player again.

[Back to the Trackhop site](../)
