# ADR-0005: FX conversion is resolved at write time and stored as effect amounts

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Entries can be in several currencies (EUR, SEK, …), banks use their own rates, and history must never be re-valued with today's rates. Groups choose either to convert to one currency (CONVERT) or to keep each currency separate (KEEP).

## Decision

Every money-bearing entry stores the original amount and currency **and** an effect amount and currency, plus an immutable `FxSnapshot` when they differ (ECB rate via Frankfurter, entered manually, or derived from the amount the bank actually charged). The balance engine only sums effects and never converts. `fxMode` is locked once a group has entries, and so is the currency of a CONVERT group.

## Alternatives Considered

### Alternative 1: Convert at read time from stored snapshots
- **Pros**: smaller rows.
- **Cons**: FX logic in the balance engine; risk of re-valuing history by accident.
- **Why not**: more complex engine and easier to get wrong.

### Alternative 2: Single currency per group only
- **Pros**: simplest group UI.
- **Cons**: mixed-currency trips that should stay separate can't be modeled.
- **Why not**: the owner wanted both modes per group.

## Consequences

### Positive
- The engine is trivial and exact; history can't be re-valued.

### Negative
- An entry has two amount pairs, and editing date, currency or amount needs a rate re-check.

### Risks
- Wrong rate at entry time. Mitigated by the stale flag, manual and bank-actual overrides, and edit-time re-quote.
