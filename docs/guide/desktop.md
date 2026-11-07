# Trackhop Desktop (Windows, macOS, Linux)

> Out of beta since 0.8.1. Keep a copy of the data folder (or save the set-up from *Tools › Backup &
> restore*) and [report](reporting-a-problem) what goes wrong. The unofficial connectors stay an alpha.

The same sync engine as the Android app, as a program on your computer, with nothing else to
install: no Docker, no Java. It runs in the background with an icon next to the clock, shows its
pages in your browser, and runs scheduled syncs while the computer is on. It is the
[Docker version](docker) packed as an app, so what works there works here, and the other way round.

## Install

The current version is **1.8.1** ([what's in it](https://github.com/xlollx/Trackhop/releases/tag/server-v1.8.1)); every version is in the
[Desktop & Server releases](https://github.com/xlollx/Trackhop/releases?q=server-v&expanded=true)
(tagged `server-v…`: Desktop and Docker have their own version, separate from the app's).

| System | Download | How |
|---|---|---|
| Windows 10 or 11 | [Installer (.msi)](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-windows.msi) | Open it. It installs for your user only, no administrator needed, with a Start menu entry. |
| Windows, no install | [Portable (.zip)](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-windows.zip) | Unzip anywhere (Documents, a USB stick) and run `Trackhop\Trackhop.exe`. |
| macOS 11 or later, Apple silicon | [.dmg (arm64)](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-macos-arm64.dmg) | Open it, drag *Trackhop* into *Applications*; the first time, [Open Anyway](#macos). |
| macOS 11 or later, Intel | [.dmg (x64)](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-macos-x64.dmg) | The same, for Intel Macs. |
| Debian, Ubuntu, Mint… | [.deb](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-linux.deb) | `sudo apt install ./Trackhop-Desktop-v1.8.1-linux.deb`, then *Trackhop* in the menu. |
| Any other Linux (x86-64) | [.tar.gz](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-linux.tar.gz) | Unpack and run `Trackhop/bin/Trackhop`. |
| Linux with Flatpak | [.flatpak](https://github.com/xlollx/Trackhop/releases/download/server-v1.8.1/Trackhop-Desktop-v1.8.1-linux.flatpak) | `flatpak install --user Trackhop-Desktop-v1.8.1-linux.flatpak` (Flatpak offers GNOME's runtime from Flathub), then *Trackhop* in the menu. |

### Package managers and stores

Scoop, Chocolatey, Homebrew, the Microsoft Store and the Snap Store have Trackhop Desktop already; the others are on their way,
and their commands do not work until the *(soon)* goes away here:

| Where | Command |
|---|---|
| winget (Windows) | `winget install xlollx.Trackhop` *(soon, in review)* |
| Chocolatey (Windows) | `choco install trackhop` |
| Scoop (Windows) | `scoop bucket add xlollx https://github.com/xlollx/scoop-bucket`, then `scoop install xlollx/trackhop` |
| Homebrew (macOS) | `brew install --cask xlollx/tap/trackhop` |
| Microsoft Store (Windows) | [Trackhop Desktop on the Store](https://apps.microsoft.com/detail/9PFX74Q2XN9P), or `winget install 9PFX74Q2XN9P` (official connections only, see [Services](#services)) |
| Flathub (Linux) | `flatpak install flathub io.github.xlollx.Trackhop` *(soon)* |
| Snap Store (Ubuntu and others) | `sudo snap install trackhop`, or *Trackhop* in Ubuntu's App Center |

**Windows warns about an unrecognised app** ("Windows protected your PC"): the installer is not
signed with a paid certificate, as is usual for open-source projects. Choose *More info*, then
*Run anyway*. The files are built by GitHub from this repository's code, in the
[Desktop workflow](https://github.com/xlollx/Trackhop/actions/workflows/desktop.yml).

To update, install the newer file over the old one: your syncs and sign-ins stay. Trackhop says when a
new version is out (**Settings › Updates**, where it can be turned off); installed from a store or a
package manager, that updates it instead.

### macOS

From 0.5.4, each release has two disk images: `-macos-arm64.dmg` for Apple silicon (M1 and later)
and `-macos-x64.dmg` for Intel Macs (*Apple menu › About This Mac* says which). Open the `.dmg` and
drag **Trackhop** into *Applications*.

**macOS refuses to open it the first time**: Trackhop is not signed with Apple's paid developer
certificate, as is usual for free open-source apps. Once, after that first refusal: *System
Settings › Privacy & Security*, scroll down to "Trackhop was blocked…" and choose **Open Anyway**,
then confirm. From then on it opens normally. (In Terminal, `xattr -dr com.apple.quarantine
/Applications/Trackhop.app` does the same.)

On the Mac, Trackhop shows its pages in your browser (no window of its own yet) and puts its icon
in the menu bar, with **Open Trackhop** and **Quit Trackhop**; it has no *Start with the computer*
yet: add it in *System Settings › General › Login Items* if you want it. Its data is in
`~/Library/Application Support/Trackhop`. The command line is
`/Applications/Trackhop.app/Contents/MacOS/trackhop-cli` ([the trackhop command](cli)).

## Use it

Start **Trackhop**: its window opens (from 0.3.0; it uses the system's web engine, Edge's WebView2
on Windows and WebKitGTK on Linux). Add services, create syncs, run them, as in the
[Docker guide](docker#services): the pages are the same. The same pages are also at
`http://127.0.0.1:8642` in any browser on this computer (*Open in the browser* in the tray's menu).
Closing the window leaves Trackhop running in the tray, for the scheduled syncs.

**Settings** (the last tab): a light or dark look, the home page's side column (the last two weeks,
the numbers, the latest runs; on the left, on the right or hidden), and the notice when a new
version is out. Installed from a store (Microsoft Store, Flathub, Snap Store, Homebrew, Scoop,
Chocolatey), that store updates Trackhop and the notice stays off.

The icon next to the clock has **Open Trackhop**, **Open in the browser**, **Start with the
computer** (it then starts hidden when you sign in, and scheduled syncs keep running) and **Quit**.
Starting Trackhop again while it runs just brings its window up. Some Linux desktops (GNOME without the *AppIndicator*
extension) have no icons there: use **Quit Trackhop** at the bottom of the page instead.

Only this computer can reach the page: it listens on `127.0.0.1` and asks for no password.

## Services

As on the [server](docker#services): Spotify, YouTube and TIDAL with your own key; Navidrome and
other Subsonic servers, Jellyfin, Plex; Last.fm and ListenBrainz as sources; SoundCloud public
links; CSV files; YouTube Music with pasted cookies (unofficial, alpha). Deezer is not there yet on
the desktop (it wants an `https` redirect). Amazon Music, Spotify without a key and Apple Music
are not there yet either: they need a browser inside the app, which is the next step.

A service can be added more than once (two Jellyfin servers, two Spotify accounts), each with its
own sign-in and name, and synced to each other; Jellyfin also signs in with Quick Connect. See
[more than one account](docker#more-than-one-account-or-server) and
[Quick Connect](docker#jellyfin-quick-connect).

The **Microsoft Store** version offers only the official connections, the personal servers,
Last.fm, ListenBrainz and files (store rules, as for the app on Google Play): for the unofficial ones
(YouTube Music, Spotify and Deezer with cookies, SoundCloud links) use the installer or the ZIP.

**Your own key.** Register this redirect with the service: `http://127.0.0.1:8642/callback`
(Spotify and Google both accept plain `http` for `127.0.0.1`). The service's page in Trackhop
shows the exact address and the steps.

## Notifications

Settings › Notifications sends a short message to Telegram, ntfy, Gotify, Discord, Slack, Matrix or
Pushover when a sync changes something, fails or waits for you, while Trackhop Desktop runs. How to
set up each one: [Notifications](docker#notifications), the same as on Docker.

## Command line

Desktop comes with `trackhop-cli`, to run syncs, read their results and add or remove tracks from a
terminal, a script or Task Scheduler while Trackhop is running: `trackhop-cli run --all --wait`.
Where it is and every command: [the trackhop command](cli). It also lets an AI assistant (Claude Desktop, Claude Code…) use Trackhop through MCP: [how](cli#with-an-ai-assistant-mcp).

## Where things are

| | Windows | Linux |
|---|---|---|
| Data (syncs, sign-ins, log) | `%APPDATA%\Trackhop` | `~/.local/share/trackhop` (`~/snap/trackhop/common` with the snap) |
| The program | `%LOCALAPPDATA%\Trackhop` (MSI) | `/opt/trackhop` (deb) |

Back up the data folder and you have backed up Trackhop. The log is `songport.log` there, and in
the page under *Log*.

**Settings** are the [server's variables](docker#settings), for whoever needs them: for example
`SONGPORT_PORT` if another program already uses port 8642 (then register the new address with the
services), or `SONGPORT_DATA` for another data folder. A password does not apply here.

## Remove it

Windows: *Settings → Apps → Trackhop → Uninstall*. Linux: `sudo apt remove trackhop`. The data
folder stays: delete it too to remove everything.

[Back to the guide](./)
