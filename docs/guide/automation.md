---
title: Run playlist syncs from Tasker, MacroDroid or a shortcut
description: Start Trackhop syncs from Tasker, MacroDroid, Automate or Samsung Modes and Routines, and react when a sync ends. Free and open source.
---

# Automation on Android

Trackhop's Android app can take commands from automation apps (Tasker, MacroDroid, Automate…)
and tell them when a sync ends. It is **off until you turn it on**: any app on your phone can send
these commands, so Trackhop ignores them unless you choose otherwise.

Turn it on in **Settings › Notifications › Automation**.

## Commands

Send a *broadcast intent* (in Tasker: Task › System › Send Intent, target *Broadcast Receiver*):

| Action | Extra | What it does |
|---|---|---|
| `com.xlollx.songport.action.RUN_ALL` | none | Runs every sync |
| `com.xlollx.songport.action.RUN` | `sync` = the sync's name (any case) or its id | Runs that sync |

A command sent while automation is off is ignored, and the log says so (Settings › Advanced › Sync log › Export log).

## The end of a sync

When a sync ends, Trackhop sends the broadcast `com.xlollx.songport.event.SYNC_FINISHED` with
these extras: `sync` (name), `id`, `added`, `removed`, `notFound`, `toReview` (numbers), `ok`
(true/false) and `error` (the first part of the error, when it failed). In Tasker: Event › System ›
Intent Received, action `com.xlollx.songport.event.SYNC_FINISHED`; the extras are local variables
(`%added`, `%ok`…). Nothing about your tracks or accounts is sent.

## Shortcuts

Long-press Trackhop's icon: besides *Sync all* and *New sync*, the syncs you ran last have their
own shortcut. Drag one to the home screen, or start it from Samsung Modes and Routines, Google
Assistant or any launcher that opens app shortcuts. Shortcuts work whether automation is on or not:
only you can tap them.

On Trackhop Desktop and the Docker server, the same is done with [the `trackhop` command](cli.md)
and the REST API.
