# Which Trackhop? Versions, plugins and community

Trackhop comes in two kinds of builds for phones, with the same code and the same look. What changes
is which services they can reach, and how.

| | GitHub and F-Droid | Stores: Google Play, Huawei AppGallery |
|---|---|---|
| Spotify, YouTube Music, Apple Music, Amazon Music, Deezer, SoundCloud through their web players | **built in**: sign in and go | through the optional Trackhop Bridge plugin |
| Spotify, YouTube Music, Apple Music, TIDAL, Deezer through their official APIs | yes | yes |
| Navidrome/Subsonic, Jellyfin, Emby, Plex, Last.fm, ListenBrainz, files | yes | yes |
| SoundCloud public links | yes | through the plugin |
| LiveOne, Anghami, Qobuz (web player), NetEase Cloud Music and QQ Music links, TikTok | yes | through the plugin |
| Gemini Nano (AI on the phone, no key) | GitHub: yes · F-Droid: no (no Google libraries there) | Play: yes · AppGallery: no |
| Ads | none | none |
| Updates | F-Droid, Obtainium or the GitHub release page | the store |

Huawei phones: [AppGallery](https://appgallery.huawei.com/app/C119677919). Samsung phones: Google Play, GitHub or F-Droid (the Galaxy
Store takes Android apps from companies only).

**Which one to choose.** If you want to move playlists between the big streaming services without
setting anything up, take the [GitHub or F-Droid build](https://github.com/xlollx/Trackhop/releases/latest):
everything is inside. The store builds suit people who sync with their own music server, with files,
or through the services' official APIs, and who prefer installing from their phone's store.

The official APIs have limits that are not Trackhop's choice: Spotify accepts five users per
developer app unless you register your own (the app guides you, and Spotify requires Premium for
it), YouTube's daily quota is shared by everyone using the same key, Apple Music needs a
developer token and cannot remove tracks that way (the Apple ID sign-in can), Amazon Music has no open API at all.

## Switching between builds

The GitHub, F-Droid and AppGallery builds share the same signing key: one installs
over the other. Google Play signs with its own key, so Play and the others cannot update each other.
To move to or from Play, take your set-up with you in a backup: the file is the same in every build
(and in Trackhop Desktop and Docker).

1. **Export a backup** (Settings › Backup & restore) and keep the `.trackhop` file outside the app, in
   Downloads or a cloud drive: uninstalling deletes the app's own data.
2. **Uninstall** the build you have.
3. **Install the other build** and restore the backup from Settings › Backup & restore.
4. **Sign in to your services again.** Moving to GitHub or F-Droid: the web connectors are built in,
   so the connector plugin is no longer needed. Moving to Play or AppGallery: install the plugin
   for the services that need it.

What comes back: syncs, services added, matches you chose by hand, ignored tracks, settings, File
playlists and, if you included it, the history. What never travels: sign-ins, passwords and client
secrets. Scheduled syncs arrive turned off ("Scheduled on …"), so they never run on two devices at
once: turn them back on with one tap.

## Connector plugins

Trackhop has an open connector interface: a separate app can hold the sign-in to a service and
answer Trackhop's requests for playlists and tracks. The store builds list the plugins installed on
the phone in Settings › Plugins and offer their services in Accounts; they never name, link or
download one. How the interface works, and the plugins known so far, are on the
[connector plugins](../plugins) page.

The reference plugin is **[Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge)**, by the
same author: it gives the store builds the same web connectors the GitHub build carries inside. It is
distributed on GitHub only. Read its notes before installing: those web interfaces are not official
APIs, they are against the services' terms, and they can stop working without notice.

## Community

- **Questions, ideas, problems:** [GitHub issues](https://github.com/xlollx/Trackhop/issues/new/choose).
  The diagnostic report in the app makes a report useful; see
  [Reporting a problem](reporting-a-problem).
- **Bridge problems:** [Trackhop Bridge issues](https://github.com/xlollx/Trackhop-Bridge/issues/new/choose).
- **Writing a plugin:** the [connector plugins](../plugins) page documents the interface; open an
  issue to have yours listed there.
- **Translations:** corrections to any of the twelve languages are welcome as issues or pull requests.

[Back to the guide](./)
