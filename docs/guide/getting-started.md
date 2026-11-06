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
On **Jellyfin** you can also sign in with **Quick Connect**: enter the server address, then approve the
code from a device where you are already signed in (no password, so it works with single sign-on).
On **Ampache**, connected as a Subsonic server, the password is not your account password: see
[Troubleshooting](troubleshooting#personal-servers).
Files need nothing.

After connecting, *⋮ → Test connection* on the account checks sign-in, playlists and search in one go.

**More than one account of a service**: add the same service again (*Accounts → Add connector*), for
example two Spotify accounts or two Jellyfin servers, and give each a name. A sync between two
accounts of the same streaming service copies the tracks as they are, with no search; between two
servers of your own the tracks are matched as usual. On Desktop and Docker the same is on the
*Services* page (*Add another account*).

## The first sync

*Syncs → New sync*: pick the source (a playlist, your liked songs, a library section, or a link), the
target (an existing playlist or "create a new playlist"; leave the name empty to reuse the source's),
and a schedule: every few hours, daily, **weekly on the day you pick** ("Every Saturday at 09:00"), or
on chosen days. Options worth knowing:

- **Mirror removals**: tracks removed from the source are removed from the target too. A run that
  would remove a large part of the target stops and waits for you to confirm it in the preview.
- **Keep every track (archive)**: nothing is ever removed from the target; only new tracks are
  added, at the end. Made for playlists that change by themselves (Discover Weekly, Release Radar,
  a daylist, a chart): the target keeps every song they ever had. When the source is one of them,
  the editor asks right under it: *Copy the latest version (replace)* (mirror removals, big turnovers
  expected) or *Keep every track (archive)*. An archive hides the removal and order options.
- **Keep the source order**: after adding, the target is put in the source's order (Spotify, YouTube
  Music, Deezer).
- **Wi-Fi only**, **Enabled**, and the matching rules.

Open the **preview** before the first run: it shows what will be added and removed. Tracks that were
not found go to the **review**, where you search the target yourself or pick a suggestion; the app
remembers your choice.

## With one service

Trackhop is useful with a single service too. With one connected, the empty *Syncs* tab (and the
message after the first sign-in) offers ready-made ideas; each opens its page filled in, to check and
save:

- **Save your weekly playlist every week**: Discover Weekly on Spotify (quick route), Discover Mix
  on YouTube Music (quick route), New Music Mix on Apple Music (once added to your library), kept as
  an archive in a playlist of your own on the same account.
- **Back up all your playlists**, on a schedule (the Backup page).
- **Liked songs as a playlist you can share**, on the same service, kept up to date.
- **Your country's chart in your library**: Apple Music's Top 100 of your country (or Deezer's
  worldwide chart) as a playlist on your service, the latest version every day.
- **Clean up duplicates** (Tools) and **your Wrapped** (Discover).

A sync between two playlists of the same account adds the source's own tracks, with no search. A
small *Ideas* card stays on the Syncs tab while you have only one service and a few syncs; close it
with ×.

Spotify does not let the official connection (your own developer key) read the playlists Spotify
makes itself (Discover Weekly, Release Radar, daylist): they look empty or missing. That is Spotify's
rule, not an error; the quick route reads them. See [Troubleshooting](troubleshooting#spotify).

[All guide pages](./)

## Appearance

Settings › Appearance has the theme (follow the system, light or dark) and **Dynamic colours**: on Android 12 and
newer the app can take the colours of your wallpaper (Material You) instead of its own indigo and magenta. It is off
by default; the title banner keeps the brand colours either way. The app follows the system font size, and above
130% the bottom bar shows the name of the selected tab only.

## Order and colours

Sync all and the widgets follow the order of the list. Hold a card and drag it to its place, or use
*⋮ → Move to position* and type the number. *⋮ → Colour* gives a card one of nine colours or any colour
of your own (the last circle). On Desktop and Docker: the arrows on each card, or the position field
on the sync's page.
