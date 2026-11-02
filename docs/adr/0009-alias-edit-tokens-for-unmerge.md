# ADR-0009: Each merged alias exposes its own edit token for unmerge

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (M0 planning)

## Context

Every edit carries an `EditToken` (generation + version of the row it writes) so stale edits fail with `StaleVersion` (spec §5.1). Merge writes only the source row (`mergedIntoId`), and unmerge clears it again, so unmerge needs a token for the **alias's** row (spec §5.6). After a merge, `observePerson(alias)` resolves to the canonical person and returns *that* person's token, and `PersonDetail.aliases` listed aliases as plain `PersonRef`s. The UI had no way to get a valid unmerge token.

## Decision

`PersonDetail.aliases` is a `List<AliasView(person: PersonRef, mergedInto: PersonRef, token: EditToken)>`. `token` is the alias row's own token, and `mergedInto` is the record it points at directly (it matters for nested merges). The UI unmerges with `PeopleApi.unmerge(alias.person.id, alias.token)`; the op validates that token against the alias row as before.

## Alternatives Considered

### Alternative 1: Unmerge takes the canonical person's token
- **Pros**: no new type; the UI already has that token.
- **Cons**: the check runs against a row unmerge doesn't write.
- **Why not**: a concurrent edit to the alias row would go undetected.

### Alternative 2: Unmerge takes a `basis` instead of a token
- **Pros**: simplest for the UI.
- **Cons**: catches a restore (`LedgerReplaced`) but not a stale version.
- **Why not**: every other edit of an existing row is token-checked; unmerge shouldn't be the exception.

### Alternative 3: Keep the gap and let the UI remember the token from before the merge
- **Pros**: no contract change.
- **Cons**: breaks after an app restart or any later write to the alias.
- **Why not**: only workable in a test, not in a UI.

## Consequences

### Positive
- Unmerge keeps the same concurrency guarantee as every other edit.
- Nested merges are visible: the UI can show which record each alias points at and unmerge any of them.

### Negative
- One more read-model type in `api` (`AliasView`).

### Risks
- None significant; the change was made in the M0 plan before any code exists (Parts 3 and 7), so there is no migration.
