---
title: Getting started
i18n: start
---

# Getting started

**Songport is now Trackhop**: the same app with a new name (an unrelated service called "SongPort" works in the same space); it updates normally, keeps all its data, and old links keep working.

## Install

| Where | Notes |
|---|---|
| [F-Droid](https://f-droid.org/packages/com.xlollx.songport/) | Built by F-Droid from source, signed with the same key as the GitHub APK (reproducible build), so the two update each other. New versions arrive a few days after GitHub. |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Add `https://github.com/xlollx/Trackhop`: every GitHub release reaches the phone by itself. |
| [GitHub Releases](https://github.com/xlollx/Trackhop/releases/latest) | The signed APK of every version. |

Android 8.0 or newer.

## Connect a service

*Accounts → Add connector.* Many services can be reached two ways:

- **Quick route** (Spotify, Apple Music, YouTube Music, Amazon Music): you sign in on the service's own
  page inside the app, as in a browser. Nothing to create. It is not an official interface, so it can
  stop working when the site changes (the app warns before the first sign-in).
- **Official route** (Spotify, YouTube Music, TIDAL, Deezer, Apple Music): you create a free developer
  key in your own name; the app walks you through it in about two minutes, step by step.

Personal servers (Navidrome/Subsonic, Jellyfin, Plex) need only the server address and your login.
On **Ampache**, connected as a Subsonic server, the password is not your account password: see
[Troubleshooting](troubleshooting#personal-servers).
Files need nothing.

After connecting, *⋮ → Test connection* on the account checks sign-in, playlists and search in one go.

## The first sync

*Syncs → New sync*: pick the source (a playlist, your liked songs, a library section, or a link), the
target (an existing playlist or "create a new playlist"; leave the name empty to reuse the source's),
and a schedule. Options worth knowing:

- **Mirror removals**: tracks removed from the source are removed from the target too. A run that
  would remove a large part of the target stops and waits for you to confirm it in the preview.
- **Keep the source order**: after adding, the target is put in the source's order (Spotify, YouTube
  Music, Deezer).
- **Wi-Fi only**, **Enabled**, and the matching rules.

Open the **preview** before the first run: it shows what will be added and removed. Tracks that were
not found go to the **review**, where you search the target yourself or pick a suggestion; the app
remembers your choice.

[All guide pages](./)

## Appearance

Settings › Appearance has the theme (follow the system, light or dark) and **Dynamic colours**: on Android 12 and
newer the app can take the colours of your wallpaper (Material You) instead of its own indigo and magenta. It is off
by default; the title banner keeps the brand colours either way. The app follows the system font size, and above
130% the bottom bar shows the name of the selected tab only.
