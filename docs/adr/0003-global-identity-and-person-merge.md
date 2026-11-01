# ADR-0003: Stable global "me" identity and person merge as the linking primitive

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Each device creates its own records for people, so "Alice" on my phone and Alice's own "me" on hers are different records for one human. Phase 5 sync must unify them without breaking the history that references the local record. The owner wanted this solved in the MVP so that people can be created locally now and linked when they register.

## Decision

The me person's UUID is generated once at first launch and is the user's permanent global identity (kept on restore, never regenerated). Each installation has a device ID, never backed up, recorded on every revision. `Person.mergedIntoId` turns a person into an alias of a canonical person (flat aliases only; me is never merged); `IdentityResolver` maps every ID to its canonical person at read time, and history is never rewritten. Phase 5 linking and placeholder claiming reuse merge.

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
- Alias chains or merge cycles. Prevented by the flat-alias rule and re-pointing within one transaction.
