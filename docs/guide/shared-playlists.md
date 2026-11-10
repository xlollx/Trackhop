---
title: A playlist shared by a group, each on their own service
description: Share one playlist with friends or family who use different music services (Spotify, TIDAL, Apple Music, YouTube Music, Deezer, your own server), kept in step through a folder you already share. Free and open source, no account.
---

# A playlist shared by a group, each on their own service

A **shared playlist** is one playlist for a group of people who don't all use the same service:
one listens on TIDAL, another on Spotify, a third on YouTube Music. Each of you keeps your own copy
on your own service, and Trackhop keeps the copies in step: a song added by one of you shows up
for the others, a song taken out goes from everyone's copy.

There is no public playlist and no Trackhop server in between. The group's changes travel through
a **folder you already share**: a Google Drive, Nextcloud or Dropbox folder, or a Syncthing folder.

## Who changes what

- **Curators** change the playlist for everyone: what they add or take out reaches the whole group.
  The person who creates the group is the first curator, and can make anyone else a curator too.
  A group always has at least one.
- Everyone else **follows**: they receive the changes, nothing they do reaches the others. Each
  person who follows picks how their own copy behaves:
  - **Exact copy**: their playlist stays the same as the group's; a song they add themselves is
    taken out again, a song they take out comes back.
  - **Keep my additions**: what they add stays in their own playlist only, and is never sent.

Roles are kept by the app itself, not just shown on screen: every change is signed with its
author's key, and the list of curators is signed by the group's creator. A change from someone
who isn't a curator is ignored by everyone, whatever app wrote it.

## When a service doesn't have a song

Every member's Trackhop looks each song up on their own service, the way any sync does. A song that
your service can't find is **never removed for the others**: it waits in your sync's review, where
you can pick a match by hand or leave it out of your copy.

## When two people change the same song

The most recent change wins. If one curator takes a song out and another adds it back later, it
stays. A song taken out stays out: an old copy of the group's files (a sync app's conflict copy,
say) doesn't bring it back.

## Step by step

**Creating the group**

1. *Tools → Shared playlists → Create a group*, or *⋮ → Share with a group* on a sync's card.
2. Pick **your service and your playlist** (the songs it holds become the group's), give the group
   a name and say how the others will see you.
3. **Choose the shared folder**: the folder your group shares, as your phone sees it (in the
   Drive, Nextcloud or Dropbox app's place in the picker, or the Syncthing folder on the phone).
4. On the group's page, **send the invite**: the others scan the QR code with their phone's camera,
   or you send them the link.

**Joining**

1. Open the invite link on the phone (or send it to Trackhop with *Share*), or paste it in
   *Tools → Shared playlists → Join with a link*.
2. Pick **your service**: Trackhop makes a new playlist there, named after the group.
3. **Choose the shared folder**, the same one the group uses.
4. The playlist fills up with the group's songs on the first sync. You join as someone who
   follows; the creator can make you a curator from the group's page.

## The group's page

- **Sync now**: sends your changes and brings in everyone else's. The group's syncs also run on
  their own, like any other sync; their cards wear a group mark.
- **Invite**: the QR code and the link, to add someone else.
- **Members**: who is in the group, on which service, and their role. The creator changes roles
  here.
- **Who changed what**: the group's log, newest first.
- **Change folder**, if the shared folder moved, and **Leave group**: you stop receiving changes,
  your playlist stays as it is on your service, and the songs you added stay with the group.

## Good to know

- Everyone in the group needs access to the shared folder: inviting someone to the group means
  sharing the folder with them too, in the app the folder belongs to.
- Each member has one file in the folder, written by their Trackhop. Leave the files alone; a copy
  a sync app makes on a conflict is fine, Trackhop reads it too.
- The group's key stays on your phone: a configuration file leaves the group's syncs out. On a new
  phone, join the group again with the invite.
- Large removals wait for a confirmation in the sync's preview, as in any two-way sync.

Related: [keep playlists in sync](sync-playlists) between two services of your own, and
[shared links](shared-links) to send someone a song or a playlist on their own service.
