# Troubleshooting

Every error shown in the app is also in *Settings → Diagnostics*. If what you see is not here, see
[Reporting a problem](reporting-a-problem).

## Spotify

**"Spotify refused the request (Forbidden)" with your own key.** Since February 2026, apps in
Spotify's *Development Mode* can only read the playlists the signed-in account created or
collaborates on: not those of other users, nor those made by Spotify itself (Discover Weekly,
Release Radar, editorial mixes). Your own playlists and liked songs work. They also serve only the
accounts listed in the app's *User Management* on developer.spotify.com. For someone else's
playlist, copy its tracks into a playlist of your own in Spotify and sync that one, or use the quick
route (*Spotify (web)*), which reads any playlist the account can open.

**"Spotify did not hand out a token".** The web session needs a fresh sign-in: *Accounts → Spotify
(web) → Disconnect*, then connect again.

## Apple Music

**"The library requires an active Apple Music subscription" (CloudLibrary).** Apple only lets
subscribers read and edit a library. Without a subscription Apple Music cannot be a source or a target.

**Tracks are not removed from Apple Music.** Apple's API cannot remove tracks from a playlist: mirror
syncs towards Apple Music only add, and the report says so.

## Amazon Music

**"The Amazon session has expired" / "served the player to a signed-out visitor".** *Accounts → Amazon
Music → Disconnect*, then connect again. If the sign-in screen does not close by itself, tap *Done*.

**"Amazon Music updated its web player (… is no longer known)".** Amazon changed its player; the
connector needs an update. See [Amazon Music capture](amazon-music-capture) and open an issue.

**Renaming and visibility are missing for Amazon playlists.** Not available since Amazon's October
2026 player: their calls have not been seen yet.

## YouTube Music

**Long pauses during a big first sync.** Google slows down an address after many searches; the app
waits the time it is told and resumes. A first sync of a thousand tracks can take an hour or two;
later runs only search what is new.

**Official route: "quota exceeded".** The YouTube Data API allows about 100 searches a day per key.
Use the quick route for big playlists.

## Personal servers

**Ampache: "Ampache refused the password".** Trackhop connects to Ampache through its Subsonic
API, with *Subsonic / Navidrome*. Ampache checks those sign-ins against a separate **Subsonic
password**, never against the password you use on its web page. In Ampache open *Preferences →
Account*, set a *Subsonic Password* (or copy your *API key*), save with *Update Account*, and enter
that in Trackhop as the password. The username stays the same.

**Plex: where does a new playlist go?** A sync to "create a new playlist" makes a music playlist on
the server, as Plex's own apps do; it shows up under *Playlists* in Plex.

## Scheduled syncs do not run

Some phones (Samsung, Xiaomi, Huawei, Oppo) stop background apps. *Settings → Battery* excludes
Trackhop from battery optimisation. A scheduled run the system interrupts resumes by itself
("paused by the system … will resume" in the log is normal).

## A sync wants to remove many tracks

With *mirror removals* on, a run that would remove more than 10 tracks and a quarter of the target
or more is held back ("Removals held back"; the sync's status reads "Removals on hold"). Tap the
status to open the **preview**: if the source really lost those tracks, tick *Remove these tracks*
and run it from there; if not, the service listed the source incompletely and nothing should be
removed. Scheduled runs never remove that many on their own. Duplicates in the
target (the same song twice, in two versions) also show up here.

A sync set to **Keep every track (archive)** never removes anything, so it is never held back: its
target only grows.

## A pasted link is not recognised

Use the playlist's share link (open.spotify.com/playlist/…, music.youtube.com/playlist?list=…,
music.apple.com/…, short spotify links too). Album and artist links are not playlists.

## Pause and stop do not react at once

They act between steps. While one long step runs (reading a big playlist, starting a web player) the
card says "after the current step".

[All guide pages](./)
