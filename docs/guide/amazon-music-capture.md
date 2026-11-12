# Amazon Music capture

Amazon has no open API for independent apps (its Web API is a closed beta for approved partners;
access was requested in October 2026, case 22725377691). The connector therefore speaks the same
interface as Amazon's own web player, and has to follow it when Amazon changes it. That happened in
October 2026, when the player moved from the "skill" API (`*.web.skill.music.a2z.com/api/…`) to
GraphQL (`gql.music.amazon.<tld>`, persisted queries).

## Signs that Amazon changed something

- *"Amazon Music updated its web player (… is no longer known)"*: a persisted query hash changed.
- *"the Amazon Music player loaded but made no calls … [requests: …]"*: the player now calls
  somewhere else; the list says where.
- The sign-in screen stays on the player and never closes by itself.

## Capturing the player's calls

1. *Settings › About*: tap the version number seven times. That turns on the developer options and
   shows the capture tools, which stay hidden otherwise (they record what is done on a site).
2. *Accounts → Amazon Music → Capture traffic*: the real web player opens inside the app.
3. Tap **Clear**, then do, in order: open *Library → Playlists*; open one of your playlists and scroll
   to the end; search for a song; create a test playlist; add a song to it; remove it; rename the
   playlist (if the site allows it); delete it.
4. Tap **Share** and send the file to the maintainer privately, never in a public issue. The file is
   deleted from the phone a day after the capture, or at once when you leave the screen and choose *Delete*.

What the file holds: the URL, method, request body and response of every call to Amazon's music
APIs, and the request's HTTP headers. Cookies are never written; credential headers (authorization,
tokens, CSRF, session) are replaced by their length, and access tokens, bearer tokens and JWTs are
blanked anywhere in the file.

## Updating the connector

`android/bridge/src/main/java/com/xlollx/songport/ytmbridge/AmazonClient.kt`:

- `HASHES` maps each operation name to its persisted-query hash: a changed hash is a one-line fix.
- Operations used: `AddToPlaylistModalUserPlaylists` (the user's playlists), `playlistDetail`
  (tracks, 100 per page, `afterCursor`), `searchTracksResult` (search, songs only; until 11/2026
  `SpecSearchPage`), `searchAlbumsResult` (search for a saved album, albums only), `CreatePlaylist`, `addTrackToPlaylist` (`DUPLICATE_TRACK` is not a failure),
  `removeTrackFromPlaylist` (by entry cursor `index:uuid`, highest index first), `deletePlaylist`.
- The player uses Apollo's automatic persisted queries: when Amazon answers
  `PERSISTED_QUERY_NOT_FOUND`, the same request goes again with the query's text, which registers it.
  The client does the same for the operations whose text is in `AmazonQueries.kt` (so far the two searches).
- Headers are rebuilt from the page configuration (`amznMusic.appConfig`: CSRF triple, device id and
  type, session id from the cookie) plus the player's application key; when Amazon refuses them, the
  headers mirrored from the real player (`AmazonBridge.HOOK`) are used.

## Without a capture

A hash is the SHA-256 of the operation's text as the player prints it, and the player's code is
public, so an operation that is still in the player can be read from it, no account needed:

    python3 scripts/amazon_queries.py --list              # the operations in the current player
    python3 scripts/amazon_queries.py searchTracksResult  # its text and hash

The text goes in `AmazonQueries.kt`, the hash in `HASHES`. A capture is still needed to learn which
operation the player uses for a step and with which variables, and for anything the script refuses
(client-side directives other than `@client`, `@connection`, `@nonreactive` and `@unmask`, which it
treats as Apollo does before sending).

[All guide pages](./)
