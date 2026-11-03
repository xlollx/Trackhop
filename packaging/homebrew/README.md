# Trackhop for Homebrew

The [Homebrew](https://brew.sh) tap of [Trackhop Desktop](https://github.com/xlollx/Trackhop) for
macOS (Apple silicon and Intel), which keeps your playlists in sync across Spotify, YouTube, TIDAL,
Navidrome, Jellyfin, Plex and more. Free and open source (AGPL-3.0).

```sh
brew install --cask xlollx/tap/trackhop
```

Trackhop is not signed with Apple's developer certificate: the first time, macOS refuses to open it.
Open *System Settings › Privacy & Security* and choose **Open Anyway** for Trackhop
([the guide](https://trackhop.org/guide/desktop#macos)). The command line is
`trackhop` while Trackhop is running.

New versions reach this tap by themselves within a few hours of their release
(`.github/workflows/update.yml`). Problems with the app itself:
[Trackhop's issues](https://github.com/xlollx/Trackhop/issues).
