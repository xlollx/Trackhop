# SoundCloud: public links as a source

SoundCloud has no public API for new apps. What does work is a **public link**: a set (playlist,
album or EP) or a user's likes page becomes the source of a sync towards any other service, with no
account.

Public links work in the GitHub/F-Droid build, in Docker and in Trackhop Desktop downloaded from this
site. The Google Play and AppGallery builds read them through the
[Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest) plugin; the Microsoft
Store build does not have them.

In the app you can also sign in with your SoundCloud account (beta, *Accounts › Add connector*):
then your own sets and liked tracks are sources and destinations too. The rest of this page is about
public links.

## Use a link

1. In SoundCloud, open the set or the profile's **Likes** and copy its link. These are recognised:
   - `https://soundcloud.com/user/sets/name`, and an unlisted set's secret link
     (`…/sets/name/s-AbC123`);
   - `https://soundcloud.com/user/likes`;
   - the app's short share links (`https://on.soundcloud.com/…`), followed to the set;
   - the same addresses on `m.soundcloud.com`.
2. In Trackhop, start a new sync and paste the link as the source (or share it to Trackhop from the
   SoundCloud app: the editor opens with it). Pick the destination as usual.
3. The sync runs like any other, by hand or on a schedule.

The same links work in the [Cards](cards): "how compatible are we?" and the playlist poster.

On the [server](docker), pick **SoundCloud (public links, unofficial)** as the source of a new sync
and paste the link in the next step.

## What to expect

- **Read-only.** A public link is never a destination: nothing is ever written there. Only a
  signed-in account writes to SoundCloud.
- **Matching** is by title, artist and duration. An ISRC and the album come through only when the
  uploader filled in the label's metadata, which official releases usually have and DJ mixes,
  bootlegs and reposts usually do not. Without it, a title written as "Artist - Title" is split, and
  otherwise the uploader counts as the artist. Expect more tracks in the review screen than from a
  streaming service, and some that exist nowhere else.
- **Likes**: only liked tracks are read; liked sets are skipped.

## When it fails

- *"This SoundCloud playlist is private or doesn't exist: only public links work"*: the set is
  private, was deleted, or the link is mistyped. A private set can be read through its secret
  link, if its owner shares it (SoundCloud's "Share" on a private set).
- *"SoundCloud changed its web player; Trackhop needs an update"*: Trackhop reads the link the way
  soundcloud.com's own player does, not through an official interface, and SoundCloud changed
  something. Update Trackhop when a new version is out, or [report it](reporting-a-problem).

[Back to the guide](./)
