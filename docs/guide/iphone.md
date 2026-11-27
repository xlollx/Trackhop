---
bridge: false
title: Trackhop on iPhone and iPad
description: Why there is no Trackhop app for iPhone (Apple's fees, its review rules, no background work) and what works instead - your own Trackhop server, whose page sits on the iPhone's home screen like an app.
i18n: iphone
---

# Trackhop on iPhone and iPad

There is no Trackhop app for iPhone or iPad, and it is not for lack of wanting one. The iPhone is the
most closed of the platforms Trackhop could run on: what is free and open on Android, Windows, macOS
and Linux, Apple either charges for or does not allow.

What works on an iPhone today is your own **Trackhop server**: it runs your syncs day and night, and
its page sits on the home screen and opens full screen, like an app.

## Why there is no iPhone app

- **Apple charges to publish anything.** An iPhone app, even a free and open-source one, reaches
  people only through the App Store, and publishing there needs Apple's paid developer membership,
  renewed every year. Trackhop is free and has no ads: it would pay Apple every year to
  give an app away. On Android the same app is on GitHub and F-Droid for free.
- **No installing from elsewhere.** On Android you can install Trackhop from its own page, F-Droid or
  Obtainium. On iPhone the ways around the App Store either cost the same membership, or need a
  computer and a fresh install every week: nothing a normal user would put up with.
- **No work in the background.** iOS pauses an app a few seconds after you leave it. A sync that runs
  every night on its own, the heart of Trackhop, is something iOS does not let an app do reliably: it
  would run only when iOS decides, often not at all. Web apps on the iPhone get even less: no
  background work of any kind.
- **Stricter rules for what an app may do.** Trackhop's quick sign-ins, which work with your usual
  Spotify, YouTube Music or Amazon Music account and no developer key, use the services' own web
  players. Apple's App Store rules want a service's permission for that, so an iPhone app would be
  limited to the official routes, each needing its own developer key, like the Google Play version
  without its plugin.
- **Apple Music's own API costs the same membership.** On Android, Trackhop reaches Apple Music through
  its web player instead, with your usual Apple ID: on the iPhone that route is closed too.

So the parts that make Trackhop easy (sign in with your account, then forget it while it syncs on its
own) are exactly the parts the iPhone takes away. A server does them instead, for every device you
have, the iPhone included.

If an iPhone app would make a difference for you, say so in an
[issue](https://github.com/xlollx/Trackhop/issues/new/choose): requests decide what comes next.

## What you need

- **A Trackhop server**: [Trackhop on Docker](docker), on a NAS, a Raspberry Pi or a computer that
  stays on. The syncs run there, so they keep going with the iPhone off or locked.
  No computer at home? [Trackhop on Azure](docker#on-azure-beta) runs in your own Azure subscription,
  always on, with an `https://` address.
- **The server within reach of the iPhone**: on the same Wi-Fi at home; from outside, through a VPN
  such as Tailscale or a [reverse proxy](docker#behind-a-reverse-proxy) with an `https://` address.
- **A password** on the server (`SONGPORT_PASSWORD`), since other devices can reach it.

[Trackhop Desktop](desktop) is not enough on its own: its page answers only on the computer it runs on.

## On the home screen

1. Open the server's address in **Safari** (for example `http://192.168.1.20:8080`) and sign in.
2. Tap **Share › Add to Home Screen**, then **Add**.
3. Open Trackhop from the home screen. The first time it asks for the password again: it keeps its
   own sign-in, apart from Safari's.

## What you can do from the iPhone

- See every sync, its last run and what changed.
- Run a sync now, turn one on or off, change its schedule.
- Make a new sync, and review the tracks that were not found.
- Everything else the server's page offers: radio syncs, notifications, lyrics, AI playlists, the
  API.

The page shows the server live and keeps nothing on the iPhone. If the server is off or out of reach,
it says so.

## Connect the services from a computer

Connect Spotify, YouTube Music and the other services from a computer's browser, not from the
home-screen app: their sign-in page can leave the app and not come back to it. Some services also
accept a sign-in only on `127.0.0.1` or an `https://` address: the [Docker guide](docker#services)
gives the rule for each one.

[Italiano](trackhop-su-iphone) · [All guide pages](./)
