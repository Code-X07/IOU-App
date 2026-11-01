# ADR-0006: Single Gradle module with Android-free domain packages

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

A small personal project with two contributors. The future-features list mentions multi-module Gradle and KMP/iOS, but the owner wants choices that suit an independent developer, not a learning showcase.

## Decision

One `:app` module in `android/`, organised by packages. `money`, `ledger.*`, `api` and `fake` contain no Android imports and no `Double`/`Float` in money paths (CI-checked), so they test on the plain JVM and can move to KMP later. Stack: Kotlin, Compose/M3, Room, Hilt, Ktor, WorkManager, Commons CSV, FastExcel (export only); minSdk 30.

## Alternatives Considered

### Alternative 1: Multi-module (`:core:*`, `:feature:*`) from day one
- **Pros**: enforced boundaries, faster incremental builds at scale.
- **Cons**: Gradle ceremony that slows the start.
- **Why not**: not needed at this size; package rules and CI checks are enough.

### Alternative 2: KMP shared module now
- **Pros**: iOS-ready.
- **Cons**: tooling overhead; iOS is a non-goal.
- **Why not**: Android-free packages keep that option open at no cost.

## Consequences

### Positive
- Fast setup; the domain is testable without Android.

### Negative
- Package boundaries are enforced by convention and CI checks, not by the compiler.

### Risks
- Android imports leaking into the domain. Caught by the CI check.
