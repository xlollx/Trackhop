---
title: Playlist sync notifications on Telegram, ntfy, Discord, Slack, Matrix, Gotify or Pushover
description: Get a message on Telegram, ntfy, Gotify, Discord, Slack, Matrix or Pushover when a playlist sync adds or removes songs, fails, has songs to review or finds new releases. In the Trackhop app, on Desktop and on Docker, with your keys kept encrypted.
---

# Notifications on Telegram, ntfy, Discord and more

Trackhop tells you on the phone when a sync changes something or fails. With **notification
channels** the same news can also reach a chat or a push service you already use: a Telegram bot,
an ntfy topic, a Discord or Slack channel, a Matrix room, Gotify or Pushover. Handy for syncs that
run at night, or on a [server](docker) nobody looks at.

## Set one up

In the app: **Settings › Notifications › Notification channels › Add a channel**. On
[Desktop](desktop) and [Docker](docker): **Settings › Notifications** on the web page.

1. Pick the kind of channel and give it a name.
2. Fill in what it needs (the steps for each are below and in the app, next to the fields).
3. Under **Tell me when**, tick the events you want:
   - a sync added or removed tracks;
   - a sync failed;
   - tracks to review;
   - removals waiting for your confirmation;
   - new releases of your artists (a weekly [New releases](new-releases) sync found new music).
4. Tap **Send test**: a test message arrives, or the channel's line says why it didn't.

Add as many channels as you like, each with its own events: failures to your phone through ntfy,
everything else to a family Discord channel, for example.

## What each one needs

- **Telegram**: make a bot with @BotFather and paste its token; send the bot a message, then enter
  your chat id (@userinfobot tells it).
- **ntfy**: subscribe to a topic in the ntfy app and enter it; the server is ntfy.sh or your own.
  On ntfy.sh anyone who knows a topic can read it: pick one hard to guess, or use an access token
  for a protected topic.
- **Gotify**: in Gotify, *Apps › Create application*, then enter the server's address and the app's
  token.
- **Discord**: the channel's *Settings › Integrations › Webhooks › New webhook › Copy webhook URL*.
- **Slack**: an app with an incoming webhook for a channel; paste its URL.
- **Matrix**: an account (a bot's is best) that is in the room: the homeserver, its access token and
  the room's id.
- **Pushover**: your user key (on pushover.net's front page) and the API token of an application you
  create there.

## Privacy

- The message holds the sync's name, how many songs were added and removed, and the error's first
  line: never your sign-ins, tokens or server addresses.
- The channels' keys are kept encrypted with your sign-ins and are never put in an exported set-up.
  A channel that comes with an imported set-up stays off until you turn it on and enter its key.
- A message only goes to the address you entered: Trackhop never follows a redirect elsewhere.
- Sending happens apart from the sync: a channel that fails is noted and never makes the sync fail.

The full details for the server, and the `trackhop notify` command, are in the
[Docker guide](docker#notifications).

[All guide pages](./)
