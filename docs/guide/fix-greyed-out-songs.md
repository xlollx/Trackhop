---
title: Fix greyed-out songs on Spotify, Apple Music, TIDAL, Deezer and YouTube Music
description: Greyed-out, unavailable songs in your playlists? Find every unplayable track and swap in a version that plays, on Spotify, Apple Music, TIDAL, Deezer and YouTube Music. Free, with Undo.
i18n: greyed-out
---

# Fix greyed-out songs in your playlists

Scroll through an old playlist and some songs are grey: you tap them and nothing plays. The track
was pulled from the catalogue, the label stopped licensing it in your country, the album was
re-released under a new ID, or a YouTube video went private. The service leaves the grey entry
where it was and does nothing else. Very often the **same recording is still there**, under
another ID, on a single, a deluxe edition or a compilation. **Trackhop** finds every unplayable
entry, proposes the version that plays, and swaps it in, in the same spot. It is free, open source,
and it works inside the one service you use.

## Why are songs greyed out on Spotify?

Spotify greys out a song when it cannot play it for you. The usual reasons:

- **Not in your country**: the rights for your region ended or never existed.
- **Not in your plan**: some tracks need Premium or a different plan.
- **Explicit filter**: the explicit-content setting hides it.
- **Removed or replaced**: the track was withdrawn, or the album was re-issued and the old entry
  points to the copy that no longer plays.

Trackhop shows the reason next to each Spotify entry when Spotify gives it. The same happens on
Apple Music, TIDAL and Deezer (songs that are no longer available), and on YouTube Music (videos
that became private or were deleted).

## How do I replace greyed-out songs?

1. **Install Trackhop** on Android ([download](/)), or [Trackhop Desktop](desktop) on a computer.
2. **Connect your service**: *Accounts → Add connector*, signing in on the service's own page.
3. Open **Tools**, pick your service under *Service for the tools*, and under **Clean up** tap
   **Unplayable tracks**.
4. Choose one playlist or **All my playlists** (your liked songs are included where the service
   has them) and tap **Check**.
5. For each unplayable entry Trackhop proposes a **Replacement** with a match percentage. A sure
   one is already selected; a doubtful one waits for you.
6. Decide entry by entry: accept the proposal, open it to **search another version** or paste a
   link, **Remove only**, or **Skip**.
7. Tap **Apply** and confirm. Each replacement is added and each dead entry removed; where the
   service can move tracks (Spotify, YouTube Music, Deezer) the replacement takes the old entry's
   place in the playlist.

Nothing changes before you tap Apply. Afterwards, **Undo last fix** puts the playlist back exactly
as it was.

## How does Trackhop know which version is right?

It asks the service which entries it can no longer play: nothing is guessed from the titles. Then
it looks for a playable copy the same way a Trackhop sync matches songs: by **ISRC** first (the
code that identifies a recording), then by title, artists and duration, with live, karaoke and
sped-up versions pushed down when the original was not one. On Spotify, when Spotify itself
already plays another edition in the dead entry's place, that edition is proposed, and it is a
sure match.

When no convincing version exists, Trackhop says so and leaves the decision to you.

## Which services does it work with?

| Service | What it finds |
|---|---|
| Spotify | Unplayable tracks, with the reason (country, plan, explicit filter) |
| Apple Music | Library playlists; Apple does not let apps remove tracks, so the playable version is added and the grey one stays |
| TIDAL, Deezer | Tracks no longer available |
| YouTube, YouTube Music | Private and deleted videos, greyed-out entries |

Amazon Music and personal servers do not tell apps which tracks they cannot play, so the tool says
so instead of guessing. The full details are in [Unplayable tracks](unplayable-tracks).

## Can it check by itself?

Yes. In *Settings*, turn on **Weekly check for unplayable tracks**. Once a week Trackhop looks
through your playlists and sends a notification when something can no longer be played. It never
changes anything on its own: the notification opens the tool, and you decide.

## Where did the song go?

If a song disappeared from a playlist altogether, rather than going grey, the **Vanished songs**
page in the **Discover** tab can tell you which ones left and when, as long as you
[back up your playlists](backup-spotify-playlists) now and then.

## More you can do with one service

- [Remove duplicate songs](remove-duplicates-spotify), the other half of *Clean up*
- [Back up all your playlists](backup-spotify-playlists)
- [Save Discover Weekly before it changes](save-discover-weekly)
- [Your Wrapped, whenever you want](wrapped-anytime)

[Download Trackhop](/) · [Getting started](getting-started) · [All guide pages](./)
