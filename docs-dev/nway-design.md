# One playlist on 3+ services (N-way groups) — design

Dev note. Status: core engine and the Android UI in place behind `Features.v2`; no server page or API yet.

## The problem

A sync is a pair (source → target), two-way with a twin. Three services today means chained pairs
(A→B, B→C, C→A or three two-way pairs): a circle. Each pair only knows its own two sides, so an
addition or a removal travels round the circle and comes back as news (echoes), and a song missing
on one service blocks or undoes the change for the next.

## Decision: a private shared playlist as the hub

Trackhop already has the engine: a shared playlist (`shared/`) is a list of tracks named by ISRC or
artist+title (not by a service id), and each member is tied to it by a two-way pair of ordinary
syncs run by `SyncEngine`. An N-way group is that, with no one else in it:

- **Group** = a `SharedGroup` with `transport = Invite.LOCAL` (new constant; `LocalGroupTransport`
  reads and publishes nothing). Its event log is the **hub**. It never leaves the device, has no
  invite (`Invite.parse` refuses the transport).
- **Member** = an existing playlist on an account (`PlaylistRef`), with a role:
  - **main**: two syncs, `hub → playlist` and its twin `playlist → hub`, both `twoWayRemovals`.
    Exactly what `SharedGroups.jobsFor` makes for a curator today.
  - **follows**: one sync `hub → playlist`, nothing on the way back. Two modes per follower:
    - *Keep my additions* (default): `twoWayRemovals` with no other half. The engine runs it as
      the half of a pair whose way back never runs: its own state (`PairState` under its id alone)
      is what the hub gave it. Songs the user adds there stay and go nowhere; a song the hub drops
      is removed only if the hub had given it; a group song the user takes out there stays out.
    - *Exact copy*: `mirrorRemovals`. Own additions are taken out, removed songs put back.
- Nothing new is stored: the group is in `StoreData.groups`, the members are derived from the jobs
  whose other side is the group (`Nway.members`). No migration: old stores have no such group.

Code: `shared/Nway.kt` (create, add, remove, setMain, setKeepMine, delete, members, order, run, restore).

Why not a new engine that diffs N snapshots: matching, search pacing, review, misses, removal
caps, locks, reports live in `SyncEngine.plan/apply` for a pair. A second engine would copy or
bypass them. Why not a star around a real playlist (A↔B, A↔C): a song A's service lacks would
never get from B to C. The hub holds any track, so it has no catalogue gaps.

## How a run works

`Nway.run(env, engine, group)` runs the group's syncs in `Nway.order`: every main member's
`playlist → hub` first, then `hub → playlist` for every member. One pass carries a change from any
main member to all. Each sync is an ordinary `engine.run(job)`:

- **Snapshot per member** = the pair's `PairState` (what both sides held after the last complete
  run), already kept by the engine. In it and gone from the playlist → removed there; on the
  playlist and not in it → added there.
- **Additions** on a main member go to the hub (a signed "+" event), then to every other member,
  searched on that member's service.
- **Removals** only come from main members: only they have a way up. The hub gets a "−" event and
  each `hub → playlist` sync takes the track out (main: by pair state; follower: mirror).
- **A follower's own changes** go nowhere. Keeping its additions they stay as the user left them;
  as an exact copy they are undone on its next run, with the usual mirror safety cap.
- **Not found on one service**: stays in the hub and on the others, listed as not found in *that
  member's* `hub → playlist` report (its review). Removing is always an explicit event, never the
  result of a failed search.
- **Conflicts**: the log is last-writer-wins per track. Removed on A while B still has it → removed
  from B. Added again on any main member after that → back everywhere. Same song added on two
  members → one hub track (same key, or matched by the engine's matcher).
- **Safety**: all existing rules apply per sync — a listing that came short writes no state and
  removes nothing; removals of a quarter or more of what the pair had in common wait for a
  confirmation in the preview; target-shrunk check; target locks (the hub is a lock key, so two
  members never write it at once).

## Why nothing echoes

1. Topology: members only talk to the hub. A tree has one path between two playlists; echoes need a circle.
2. Each spoke has its own pair state, so its own writes are never read back as changes.
3. Hub tracks have service-independent keys and the match cache ties each member's track to one:
   a track that arrives on B is recognised as the hub's when B's way up runs.

## What changed in existing code

- `SyncEngine.plan`: one line. "Another sync writes to this target → no removals" is skipped when
  the target is an N-way hub *and* the run is a two-way half: every main member writes to the hub,
  and a two-way removal only takes tracks this member's own pair held. Any other sync: unchanged.
- `SyncEngine.plan`: the two-way logic is also on for a follower that keeps its additions
  (`Nway.keepsMine`: two-way removals, no other half, source an N-way hub — no sync has that today).
- `FileStore.pairState/putPairState/dropPairState`: a sync with no other half keys its state by
  its own id. No such state exists for any other sync, so they read null as before.
- `ConfigBackup`: see Backups below.
- `Invite.LOCAL`, `LocalGroupTransport`, `SharedTransports.open` case; `SharedGroups.jobsFor`
  private → internal.

Pair syncs, twins and shared playlists with people: untouched code paths. A user's chained pairs
keep running as they are; a group is a new thing made on purpose.

## Release switch

`Nway.create` and `Nway.add` refuse without `Features.v2`. A group that exists keeps running
(same rule as the other 1.18 features).

## Scheduling, notifications, reports

The group's syncs are ordinary `SyncJob`s (twins marked `twin`, named "<group> · <service>" and
"… ↔"): the existing schedulers (WorkManager, the server's) run them, each with its report and
notification. `create/add` take one `Schedule` for all of them. Run alone and in any order they
are still correct; a change may then take a second round to reach everyone. "Sync now" on the
group should call `Nway.run` (or walk `Nway.order` through the platform's own run path).

## Backups and moving to the server

A configuration file (`.trackhop`, also inside the encrypted full backup, and what the server's
config import takes) carries the local groups (`Snapshot.groups`) and their syncs. Shared
playlists with other people stay out, as before. Not carried: the group's key (keys never travel)
and its list. `Nway.restore` gives the group a new key and an empty list; the first run there
fills the hub again from the main members. Pair states do not travel either, so that run removes
nothing. A group this device still has the key of is left as it is. Group ids from a file are
checked (they name a file on disk). The usual rule for a file from another device applies: its
scheduled syncs arrive turned off.

The one-tap "Hand over to my server" works per sync through `POST /api/v1/syncs` and refuses
two-way pairs today, so it cannot carry a group: that needs a server endpoint (follow-up). Until
then a group reaches the server by importing the configuration file there.

## Server / Desktop

Same core, so the engine is there. Not exposed: the server has no group UI or API at all today
(only mailboxes for other people's groups). Follow-up: `GET/POST/DELETE /api/v1/groups` and a web
page; `SharedGroups.create` only needs the Env's token vault and data folder, which the server has.

## The Android UI (as in the approved preview)

`ui/NwayScreen.kt`, wired in `MainActivity` (`nwayOpen`: "" = a new group, else a group's id) and `SyncsScreen`.

- **New sync › "One playlist on several services"**: a card at the top of the editor of a new sync
  (1.18, two services signed in). The page: name, the playlists with their role, "Add a playlist"
  (service, playlist or a new one, Main / Follows, and for Follows "Keep my additions" / "Exact
  copy"), the schedule, Preview.
- **First-run sheet**: per service what the first sync does, counted from the listings with the
  sync's own matcher (`Matcher.TrackIndex`), no search and nothing written: "+N on X" for the main
  ones, the size of a new playlist, what an exact copy would lose. What a service lacks is not
  known before the first run (that needs the searches): it shows on the card afterwards.
  "Create and sync" makes the new playlists, the group, schedules its syncs and runs them in order.
- **One card in Syncs** per group (services by role, schedule and last run, tracks in the group,
  "Not found: N", Open / Sync now). The group's own syncs are taken out of the list the screen
  works on (`NwayUi.memberJobIds`), so counts, search, drag order and Sync all's pill are about the
  user's other syncs; `NwayUi.storeIndex` maps a dropped card's place back to the stored list.
  With no group in the store the screen takes the store's data as it is: the old code path.
- **Group page**: each playlist with role and state (ok, N not found › Review, removals waiting ›
  Preview, error), ⋮ to change role, change the follower's mode, remove from the group; add a
  playlist; schedule, pause, delete group.
- Editing a member sync (a notification, the log) opens the group page. Local groups are not listed
  in Shared playlists, nor counted for the Tools row. The home-screen widget leaves the group's syncs out.
- "Sync now" is `Scheduler.runInSequence(Nway.order)`: one worker, the syncs one after the other
  (they all hold the "shared" account in `ParallelRuns`). Scheduled runs are still per sync.
- Every dialog and sheet keeps its state with `rememberSaveable`; `NwayFlowTest` (androidTest)
  recreates the activity under each. Not run here: this machine has no emulator.

Still to do: the same page on the Desktop/Docker web (the server has no group page to copy), a
group's rows in the widget and in "Trackhop in numbers", scheduled runs as one ordered run.

## What the UI must offer (the list the UI above was built from)

1. Create a group: name, pick 2+ playlists (account + playlist, or "create new" → the UI creates
   it first), role per member (main / follows), at least one main; one schedule. Per follower:
   "Keep my additions" (default) or "Exact copy" (`setKeepMine`).
2. A warning before the first run: main members' current tracks are merged; an exact-copy
   follower's extra tracks are removed (behind the usual confirmation when many); a follower
   keeping its additions loses nothing.
3. A group card in Syncs: the member services, roles, last run, one "Sync now" for the group,
   combined progress. The member syncs and "↔" twins hidden behind it (not as 2N−1 cards).
4. Group page: members with role switch (`setMain`), add / remove member, schedule, enable/disable,
   delete group (playlists stay).
5. Per member: its review (not found / to check) and removals waiting for confirmation, reached
   from the group; a combined summary ("+3 everywhere, 1 not on TIDAL").
6. The hub's history ("+ Band – Song", "− …") is available from the log if wanted.
7. Keep local groups out of the Shared playlists screen (`Nway.isGroup`): no invite, members,
   transport there. Editing a member sync opens the group page.
8. Optional: spot a circle of pair syncs and offer to turn it into a group.
9. Server web page: the same list of needs.

## Open questions / known limits

- One-tap hand-over of a group to the server: needs a server endpoint (see Backups).
- A follower keeping its additions reuses the two-way notes ("two-way state noted", removals held
  back when a quarter of what it shares with the group goes at once): the wording says "two-way".
  New strings wanted with the UI.
- A song the follower added itself that a main member later adds too becomes the group's: it then
  goes when the group drops it.
- A Trackhop older than this reading a file with a group gets the syncs without the group: they
  fail with "group gone" until updated. Files without groups are unchanged.
- Order is not kept the same (the hub has no order); only membership.
- A main member's playlist is read twice per group run (way up, way down). Fine for now; a group
  runner sharing one read is possible later.
- A member playlist that is also the target of another sync keeps the existing rule there (no
  removals on a shared target).
