---
title: Use your Trackhop server from the phone, hand syncs over and never sync twice
description: Connect the Trackhop app to your own Trackhop server on Docker, so the phone and the server take turns on each playlist, hand a sync over to the server and bring it back, and run and review the server's syncs from the phone. Free and open source.
---

# Your phone and your Trackhop server

> **This arrives in the next update** of the app, Desktop and Docker. This page describes it ahead of time.

Many people use Trackhop on the phone and also run the [Docker server](docker) at home, on a NAS or
a Raspberry Pi. The two can work together: they take turns on each playlist, a sync can move from the
phone to the server and back, and the phone can run and review the server's syncs.

## Connect them

1. On the server's web page, open **Tools › API** and make a token.
2. In the app: **Settings › Advanced › Coordinate with my server**. Enter the server's address
   (`http://192.168.1.10:8080`, or your reverse proxy's address) and the token. The app checks them
   and says when it is connected.

The phone must be able to reach the server: on the same network, through a VPN, or behind a
reverse proxy. [Trackhop Desktop](desktop) answers only the computer it runs on, so this works with
a Docker server.

## Never the same song twice

The same sync on the phone and on the server could both add what is missing, if they ran at once.
With the server connected, each one reserves a playlist before writing to it and gives it back at
the end. When the other one is writing, the run is skipped with a note ("Running on …: skipped")
and the next scheduled run tries again. If the server can't be reached, the phone syncs as usual
and its log says so: syncs never wait for the server.

Trackhop also guards against one sync running on two phones: a set-up restored on another phone
keeps its scheduled syncs off there until you turn them on, and the card says on which device they
are scheduled.

## Hand a sync over to the server

A sync that should run when the phone is off, every hour, all year: in its **⋮** menu, tap **Hand
over to the server**.

- The server makes the same sync and runs it on its schedule, with **its own** accounts: the app
  never sends your sign-ins. If a service is not connected on the server yet, the app tells you which
  one to connect there first.
- The card stays on the phone, marked **On server**, with the server's last run. Its run button
  starts it on the server, and its review opens what the server left.
- **Bring back to this phone**, in the same menu, deletes the sync on the server (the playlists stay
  as they are) and runs it on the phone again, with the review choices made on the server.

Radio syncs (they listen on the phone), Shazam syncs, syncs of File playlists and two-way pairs stay
on the phone.

## Tools › My server

**Tools › My server** lists the server's syncs with how each stands and its last run. Run one now,
or open its review: ignore a song that isn't on the service, or keep a match that's right (to pick
another song, use the server's page).

**Review choices on both sides.** For a sync handed over, and for one that exists on both (the same
two playlists) once you tap **Share review choices**, what you ignore, pick or keep on one side
reaches the other; for each song the newest choice wins.

## Shared playlists on your server

With the server connected, a [shared playlist](shared-playlists) can use it as the group's meeting
point (**My Trackhop server**): the other members need no account there, only the invite.

The server side, with every API call, is in the [Docker guide](docker#coordinate-with-my-server).

[All guide pages](./)
