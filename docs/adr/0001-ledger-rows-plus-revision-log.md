# ADR-0001: Ledger stored as Room rows plus an append-only revision log

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Audit history is a core requirement: friends will dispute amounts, so every change to ledger data must be recorded and viewable. Phase 5 adds multi-device sync, and future items (C1 event sourcing, C3 signed entries) build on a history of changes. The project is a small personal one, so up-front complexity has to pay for itself.

## Decision

Room tables hold the current state and serve all reads. Every write goes through a single `LedgerWriter`, which in one transaction updates the row and appends an `EntryRevision` holding a full JSON snapshot (with a schema number), author, device, action and reason. The revision table is insert/query only, and replaying it must reproduce the tables.

## Alternatives Considered

### Alternative 1: Mutable rows only (version + tombstone columns)
- **Pros**: least code.
- **Cons**: no edit history.
- **Why not**: a later migration to event sourcing would lose, for good, all history from before the migration.

### Alternative 2: Event sourcing from day one
- **Pros**: cleanest model for audit and sync merges.
- **Cons**: projectors, rebuilds and roughly double the persistence work before anything is usable.
- **Why not**: too heavy before sync exists; the revision log gives most of the audit value.

## Consequences

### Positive
- Full history, diffs and revert-to-version from day one.
- The revision log can be promoted to an event log (C1) and signed (C3) later without losing history.

### Negative
- Two writes per change; the row and the log must stay consistent.

### Risks
- Drift between rows and log. Mitigated by the single write path (DAO writes are `internal`) and a property test that replaying the log equals the tables.
