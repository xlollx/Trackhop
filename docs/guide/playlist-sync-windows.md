---
title: Playlist sync for Windows
description: Keep playlists in sync between Spotify, YouTube, TIDAL, Navidrome, Jellyfin and Plex on Windows 10 and 11, with Trackhop Desktop. Free, open source, no account. Install with the MSI, Scoop or a portable ZIP.
---

# Playlist sync for Windows

**Trackhop Desktop** keeps your playlists identical across music services from a Windows 10 or 11
PC: Spotify, YouTube and TIDAL (with your own free developer key), your Navidrome, Jellyfin, Emby or Plex
server, Last.fm and ListenBrainz, playlist files and,
unofficially, YouTube Music, Spotify and Deezer signed in with your browser's cookies. It sits next to the clock and runs your syncs
on a schedule while the computer is on. Free and open source, no account, nothing to install besides
it (no Java, no Docker, no administrator rights).

![Trackhop Desktop: the syncs, with their last result and the next run](../store/desktop/1-syncs.jpg)

## Install

| How | Details |
|---|---|
| Installer (.msi) | From the [download table](desktop#install): installs for your user only, with a Start menu entry. |
| Portable (.zip) | Unzip anywhere, even a USB stick, and run `Trackhop.exe`. |
| [Scoop](https://scoop.sh) | `scoop bucket add xlollx https://github.com/xlollx/scoop-bucket` then `scoop install xlollx/trackhop` |
| [Microsoft Store](https://apps.microsoft.com/detail/9PFX74Q2XN9P) | Install *Trackhop Desktop* from the Store, or `winget install 9PFX74Q2XN9P`. Official connections only; updates come from the Store. |
| [Chocolatey](https://community.chocolatey.org/packages/trackhop) | `choco install trackhop` |
| winget (community) | On its way ([status](desktop#install)). |

Windows may say "Windows protected your PC" the first time: the installer is not signed with a paid
certificate. Choose *More info*, then *Run anyway*.

## What you can do

- **Sync playlists on a schedule**, hourly to weekly, additions only or removals mirrored too.
- **Transfer many playlists at once**, liked songs and saved albums included.
- **Review** the tracks the target does not have, with suggested replacements.
- **Back up** every playlist as dated files, and the whole set-up in one file.
- **Radio syncs**: the songs an internet radio plays in the hours you choose, saved to a playlist.
- **Automate it**: Task Scheduler with the [command line](cli) (`trackhop run --all`), or the
  [REST API](docker#api).

![A sync's settings: schedule, mirrored removals, colour](../store/desktop/2-sync-detail.jpg)

Its pages are served only to this computer (127.0.0.1). Your data stays in `%APPDATA%\Trackhop`.

More: [the Desktop guide](desktop) · [Mac](playlist-sync-mac) · [Linux](playlist-sync-linux) ·
[on a home server](playlist-sync-nas)

[All guide pages](./)
