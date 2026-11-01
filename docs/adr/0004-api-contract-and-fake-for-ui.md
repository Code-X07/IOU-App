# ADR-0004: Core/UI boundary is a pure-Kotlin `api` package plus an in-memory fake

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Two contributors: the owner builds the core, and a friend owns the whole UI layer, ViewModels included. The friend should start as soon as possible, before real persistence exists, and the two sides must not drift apart.

## Decision

The core exposes `*Api` interfaces (Flow reads, suspend writes returning `LedgerResult<T>`, typed `LedgerError`) in an Android-free `api` package. An in-memory fake that uses the real `ledger.rules` and `ledger.engine` ships first (Milestone 0). Hilt binds the fake or the real implementation per product flavor (`fake` / `real`). Shared contract tests run against both, and changes to `api` go through small PRs that both contributors review.

## Alternatives Considered

### Alternative 1: Owner writes the ViewModels and UiState; friend writes only composables
- **Pros**: validation and error mapping stay in the owner's code.
- **Cons**: the friend is blocked on the owner for every screen's state.
- **Why not**: the owner chose to give the friend the ViewModels.

### Alternative 2: Friend works against the real implementation only
- **Pros**: no fake to maintain.
- **Cons**: the friend can't start until persistence is done.
- **Why not**: conflicts with "start as soon as possible".

## Consequences

### Positive
- UI work starts in week one; the fake doubles as a demo and UI-test backend.
- The fake gives realistic errors and balances because it shares the rules and the engine.

### Negative
- The fake must be maintained alongside the real implementation.

### Risks
- Fake/real drift. Caught by the shared contract test suites in CI.
