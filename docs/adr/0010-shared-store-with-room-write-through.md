# ADR-0010: Both flavors run on one in-memory store; Room is its write-through journal

**Date**: 2026-11-04
**Status**: accepted
**Deciders**: project owner, Claude (M1 planning)

## Context

ADR-0008 put every write rule (`ledger.ops`) and every read projection (`ledger.views`) into shared pure packages. What remained in the M0 fake was a small store: it holds the ledger as one value, runs an op under a lock, appends revisions, counts `ledgerSeq` and publishes flows. Spec §2.2 describes the real flavor's reads as Room `Flow`s mapped through the engine. Building that would mean a second store with the same responsibilities next to the fake's. Both would be guarded only by the contract tests.

## Decision

The fake's store becomes `store.LedgerStore`, shared by both flavors. It keeps the whole ledger in memory and serves every read from it. A write persists its validated plan through a `LedgerJournal` before publishing: nothing in the fake, one Room transaction (`RoomJournal`) in the real flavor. At start the real flavor loads the database named by the pointer into the store. Restore and undo replace the store's content under its write lock. The setup, people, groups, ledger and settings APIs are one set of `Store*Api` classes for both flavors.

## Alternatives Considered

### Alternative 1: Room-driven reads per spec §2.2
- **Pros**: no full copy of the ledger in memory; reads come straight from the database.
- **Cons**: a second set of API classes and a second store doing what the fake's already does; every read re-assembles `LedgerState` from Room anyway (ADR-0008's consequence); drift between fake and real becomes possible again.
- **Why not**: more code for the same behaviour at friend-group scale.

### Alternative 2: The fake's store with periodic snapshots to disk
- **Pros**: even less code.
- **Cons**: loses writes on process death; no per-write atomicity; breaks spec §5.1.
- **Why not**: the audit history and atomic writes are core requirements.

## Consequences

### Positive
- The fake and the real flavor share the store and the ledger-data APIs, so the contract tests mostly check persistence.
- Groups work in the real flavor as soon as persistence exists (M1 instead of M2).
- Reads never touch the disk; a write is one transaction plus an in-memory update.

### Negative
- The whole ledger, revisions included, lives in memory and loads at start. That is fine at friend-group scale; paging can come later.
- Spec §2.2's "Room `Flow`s" no longer describes the real flavor.

### Risks
- If the in-memory state and the database ever diverged, the app would show data that isn't stored. Mitigation: the store publishes only after the journal returned, a failed append leaves the store unchanged (`LedgerStoreTest`), and a seeded ledger written through the journal must load back identically (`RoomJournalTest`).
