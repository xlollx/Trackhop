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
- [Sync Spotify playlists to Navidrome, Jellyfin or Plex](spotify-to-navidrome-jellyfin), from your own library.
- [Export a playlist to CSV or M3U](export-playlist-csv), and back up all of them.
- [Your Shazams in a playlist](shazam): every song you Shazam, on any service.
- [Songs from a radio, in a playlist](radio): what a station plays in the hours you choose, on any service.

**Coming from another service**
- [A free Soundiiz alternative](soundiiz-alternative): the same moves, kept in sync, no account, no limits.
- [A free TuneMyMusic alternative](tunemymusic-alternative): transfers plus automatic sync, free and open source.

**Maintaining it**
- [Release process](release-process): version, changelogs, what CI does, F-Droid and Obtainium.
- [Amazon Music capture](amazon-music-capture): what to do when Amazon changes its web player again.
- [Getting the word out](getting-the-word-out): where Trackhop should be listed, and what is done.

[Back to the Trackhop site](../)
