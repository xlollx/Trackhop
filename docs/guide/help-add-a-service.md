---
bridge: false
title: Help add a music service from your country
description: Trackhop's developer cannot sign in to services that only work in some countries. How to record what the service's website does, safely, and send it so the service can be added.
i18n: help-add
---

# Help add a music service from your country

Some services only open in one country, or only take a local phone number or payment card:
Yandex Music, NetEase Cloud Music, Pandora and many others. Trackhop's developer cannot
sign in to them, so cannot see how their website talks to its servers, and without that a
connector cannot be written.

You can. You use the service anyway: record what its website does while you use it, and send the
file. That is usually enough to start a connector.

## Services we are looking for

If you use one of these, a capture helps:

- **Russia and nearby**: Yandex Music, VK Music, Zvuk.
- **China**: NetEase Cloud Music (with an account: playlists you made, adding tracks), QQ Music,
  Kugou, Kuwo.
- **Rest of Asia**: KKBOX, JOOX, LINE Music, AWA, Melon, Genie, Bugs, FLO, VIBE, Wynk, Hungama, Gaana.
- **Americas**: Pandora, iHeartRadio, SiriusXM, Claro Música.
- **Europe**: BBC Sounds.

Your service is not here? A capture is welcome all the same.

## Before you start

- **Sign in first**, then start the capture. The sign-in itself is the part we do not need, and it
  is the part with the most personal data.
- Use your own account, never someone else's.
- **Sign out of the website when you are done.** That ends the session the capture saw.

## Way 1: with the Trackhop app (Android, recommended)

The GitHub/F-Droid build of Trackhop has a capture tool. It keeps only the calls to the service's own
servers and removes what lets someone use your account (see [What the file holds](#what-the-file-holds)).
The Play Store and AppGallery builds do not have it.

1. *Settings › About*: tap the version number seven times. The capture tools appear.
2. *Settings › Advanced › Capture a website*: type the service's web address (for example
   `https://music.yandex.ru`) and tap **Open**. The real website opens inside the app.
3. Sign in, then tap **Clear**.
4. Do the steps below, in order.
5. Tap **Share** and send the file as explained in [How to send it](#how-to-send-it). When you leave
   the screen, choose *Delete*; otherwise the phone deletes it a day later.

If the shared file is almost empty, the service's website may talk to servers with another name:
use Way 2.

## Way 2: with a computer browser

Chrome, Edge or Firefox on a computer:

1. Open the service's website and sign in.
2. Press **F12** (or right-click, *Inspect*) and open the **Network** tab. Tick *Preserve log*
   (Chrome) or *Persist logs* (Firefox).
3. Click the clear button (🚫), then do the steps below, in order.
4. Right-click any line in the list, then *Save all as HAR* (in Chrome, choose the *sanitized* option
   when it is offered).
5. **Sign out of the website.** A browser file can still contain keys to your account, even the
   "sanitized" one; signing out makes them useless. Then send it as explained below.

## The steps to do

Do whatever the website allows, slowly, one at a time:

1. Open your playlists (your library).
2. Open one of your playlists and scroll to the end.
3. Search for a song.
4. Create a test playlist.
5. Add a song to it, then remove it.
6. Open your liked songs (favourites). Like a song, then un-like it.
7. Rename the test playlist, then delete it.

If you have a few more minutes, these help too:

- **Follow and unfollow**, one at a time: an artist, an album, a podcast, someone else's playlist.
- **Edit the test playlist** before deleting it: change its cover, its description and its title,
  and switch it between public and private.

A step the website does not allow is useful information as well: say so.

## How to send it

- **By email** to [xlollx@trackhop.org](mailto:xlollx@trackhop.org), with the service's name and
  your country.
- **Never attach a capture to a public GitHub issue.** You can open an
  [issue](https://github.com/xlollx/Trackhop/issues/new/choose) to say "I can capture X": the file
  itself goes by email.

## What the file holds

The address, method, request and answer of each call the website made while you did the steps.
That includes your playlists' names and tracks and your username or account number.

The app's capture tool removes what would let someone act as you: cookies are never written, sign-in
headers (authorization, tokens, sessions, CSRF) are replaced by their length, and passwords, e-mail
addresses, phone numbers and access tokens are blanked in addresses and forms. A browser file removes
less, which is why signing out afterwards matters.

The file is read once to learn how the website works, then deleted. It is never published, and the
connector is written from it by hand.

## What happens next

1. The capture shows whether the service can be added, and how: a full service (read and write your
   playlists), a source only, or not at all.
2. If it can, the connector arrives in a later version. Sometimes a second capture is needed for one
   missing step; you would get an e-mail saying exactly which.
3. Before the connector is released, you may be asked to try a test build on your account.

A service added this way works for everyone in your country, not only for you.

[Русский](help-add-a-service-ru) · [中文](help-add-a-service-zh) · [All guide pages](./)
