# ADR-0003: Stable global "me" identity and person merge as the linking primitive

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Each device creates its own records for people, so "Alice" on my phone and Alice's own "me" on hers are different records for one human. Phase 5 sync must unify them without breaking the history that references the local record. The owner wanted this solved in the MVP so that people can be created locally now and linked when they register.

## Decision

The me person's UUID is generated once at first launch and is the user's permanent global identity (kept on restore, never regenerated). Each installation has a device ID, never backed up, recorded on every revision. `Person.mergedIntoId` points a merged person at the person it was merged into; aliases form a tree, and the canonical person is the root (me is never merged). A merge writes only the source's `mergedIntoId`, and only canonical people can be merged into canonical people, so unmerge is the exact inverse of merge. `IdentityResolver` maps every ID to its canonical person at read time, and history is never rewritten. Collisions (handle defaults, shared memberships, transfers between the two records) resolve at read time without extra writes. Phase 5 linking and placeholder claiming reuse merge.

*Amended 2026-11-02 after an external review:* the first version kept aliases flat by re-pointing a source's aliases to the target. Unmerging the source then left those aliases attached to the target, so merge → unmerge did not restore balances. The tree model fixes this.

## Alternatives Considered

### Alternative 1: ID mapping/translation layer added in phase 5
- **Pros**: nothing to build now.
- **Cons**: every synced entry translated in both directions, forever.
- **Why not**: makes phase 5 much more complicated.

### Alternative 2: Natural keys (phone or email hash)
- **Pros**: automatic matching.
- **Cons**: numbers change, not everyone shares them, privacy concerns.
- **Why not**: unreliable; at most a hint for suggesting matches.

## Consequences

### Positive
- Merging duplicate contacts works today; phase 5 needs no translation layer.
- Imports from a fresh install map source-me to local me, after confirmation.

### Negative
- Canonical resolution touches every read and the validator.

### Risks
- Merge cycles. Prevented because both merge source and target must be canonical, and a state invariant checks imported data for cycles.
- Resolution follows chains instead of one hop. The resolver builds the full map once per state, so the cost is negligible.
