# ADR-0012: Expenses have payer rows; effects come from per-person contributions

**Date**: 2026-11-07
**Status**: accepted
**Deciders**: project owner, Claude (M2 design)

## Context

Until M2 every expense had exactly one payer (`payerPersonId`), and `LedgerEngine` turned each share of another person into `Effect(payer, debtor, share)`. The owner wants expenses paid by several people (Alice paid €60 and Bob €40 of a €100 dinner). `v0.1.0` is installed with Room schema 1, revision snapshots of schema 1, backups and normalized exports that all name one payer. Balances in groups are member nets (ADR-0002); outside groups they are pairwise between me and one other person.

## Decision

Every expense gets one or more `ExpensePayer` rows, owned and versioned like its shares, with a `payerSplit` of `EQUAL` or `EXACT` resolved by the existing `SplitResolver` over the effect total. `payerPersonId` is removed: the M2 migration, the schema-2 snapshot upcaster, version-1 backups and schema-1 exports all turn it into one `EQUAL` payer row paying the effect total. Effects are computed from each canonical person's contribution (`paid − share`), matching the largest creditor with the largest debtor (ties to the lowest UUID), in exact integer arithmetic.

## Alternatives Considered

### Alternative 1: Keep `payerPersonId` and add optional extra payer rows
- **Pros**: no data migration; old rows stay as they are.
- **Cons**: two shapes of "who paid" in the engine, validator, views, diffs, import and export, forever.
- **Why not**: the migration is one-off work; the dual model is a permanent cost.

### Alternative 2: Split each debtor's share across payers in proportion to what they paid
- **Pros**: flows read "Carol owes Alice 60 % and Bob 40 % of her share".
- **Cons**: rounding happens per debtor, so a payer's effects no longer add up exactly to their contribution; a cent goes missing from a member net and needs a second correction pass.
- **Why not**: the contribution matching is exact by construction, and inside groups only the nets matter.

### Alternative 3: Model a multi-payer expense as one expense per payer
- **Pros**: no schema change.
- **Cons**: shares round separately per part, so the parts don't add up to the real split; edit, delete, history and undo touch several entries.
- **Why not**: breaks "one real-world payment = one entry".

## Consequences

### Positive
- A single-payer expense gives exactly the effects and flows it had before, so the migration moves no balance.
- Payer and share rows share one resolver, one rounding rule and one FX treatment.

### Negative
- An `api` change for the UI contributor: drafts and read models carry a payer list.
- The first Room migration, snapshot upcaster and backup/export version bump all land at once.

### Risks
- Flows with several payers depend on the matching order; they are informational, and the order is deterministic.
