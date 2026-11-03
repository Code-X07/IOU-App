# ADR-0008: Write path and read models are shared pure packages (`ledger.ops`, `ledger.views`)

**Date**: 2026-11-02
**Status**: accepted (2026-11-03)
**Deciders**: project owner, Claude (M0 planning)

## Context

ADR-0004 requires the fake to behave like the real implementation, guarded by contract tests. The write rules (tokens and bases, draft → rows, FX choices, the stored-shares rule, cascades, merge, revert) and the read projections (overview, details, edit drafts, history, settle-up drafts) are the same for both. Written twice, inside the fake and inside the Room layer, they would drift, and the contract tests would only catch the drift that a test happens to exercise.

## Decision

Two Android-free packages hold this logic once. `ledger.ops`: one pure function per `api` write, `(state, input) → LedgerResult<Planned(value, WritePlan)>`, where the plan lists the changed rows (one revision each) and new FX snapshots, already validated by `LedgerValidator`. `ledger.views`: `LedgerViews(state, revisions, generation, home currency, rates)` builds every read model. The fake is a thin in-memory store that applies plans; M1's `LedgerWriter` applies the same plans in one Room transaction, and the real `*Api`s build views from Room state.

## Alternatives Considered

### Alternative 1: Logic inside the fake now, inside `data` in M1
- **Pros**: each implementation is self-contained; no extra packages.
- **Cons**: every rule is written twice; drift is likely.
- **Why not**: it undoes the point of ADR-0004's shared rules and engine.

### Alternative 2: Real implementation only, a hand-written fake with canned data
- **Pros**: least code in M0.
- **Cons**: the fake gives fake errors and balances; the UI is built against behaviour that M1 changes.
- **Why not**: the spec requires a fake that uses the real rules and engine (§6.3).

## Consequences

### Positive
- The fake behaves like the real thing by construction; contract tests mostly check storage and wiring.
- Ops and views are plain JVM unit tests; M1 mainly adds persistence, files and the network.

### Negative
- M1 must load a full `LedgerState` for each write and read projection. That is fine at friend-group scale, and paging or incremental views can come later.
- Ops can't use database features (e.g. queries) directly; everything goes through `LedgerState`.

### Risks
- Performance with very large ledgers: mitigated by the small expected size; revisit if loading the state becomes slow.
