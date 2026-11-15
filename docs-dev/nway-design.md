# One playlist on 3+ services (N-way groups) — design

Dev note. Status: core engine in place behind `Features.v2`; no UI, no server API yet.

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
  - **follows**: one sync `hub → playlist` with `mirrorRemovals`: an exact copy.
- Nothing new is stored: the group is in `StoreData.groups`, the members are derived from the jobs
  whose other side is the group (`Nway.members`). No migration: old stores have no such group.

Code: `shared/Nway.kt` (create, add, remove, setMain, delete, members, order, run), ~130 lines.

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
- **A follower's own changes** go nowhere and are undone on its next run (addition taken out,
  removed song put back), with the usual mirror safety cap.
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

## Server / Desktop

Same core, so the engine is there. Not exposed: the server has no group UI or API at all today
(only mailboxes for other people's groups). Follow-up: `GET/POST/DELETE /api/v1/groups` and a web
page; `SharedGroups.create` only needs the Env's token vault and data folder, which the server has.

## What the UI must offer (no UI written yet)

1. Create a group: name, pick 2+ playlists (account + playlist, or "create new" → the UI creates
   it first), role per member (main / follows), at least one main; one schedule.
2. A warning before the first run: main members' current tracks are merged; a follower's playlist
   becomes a copy (its extra tracks are removed, behind the usual confirmation when many).
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

- A follower is an exact copy only. "Keep my additions" exists for shared playlists per device
  identity; for a local follower it needs a small engine addition (one-way sync that removes only
  what the hub once gave). Wanted?
- Groups, like shared playlists, are left out of configuration files and "hand over to my server"
  (the group's key is per device). Should a local group travel in backups?
- Order is not kept the same (the hub has no order); only membership.
- A main member's playlist is read twice per group run (way up, way down). Fine for now; a group
  runner sharing one read is possible later.
- A member playlist that is also the target of another sync keeps the existing rule there (no
  removals on a shared target).
