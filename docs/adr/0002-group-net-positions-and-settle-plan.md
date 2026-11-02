# ADR-0002: Group balances are member net positions with a greedy settle plan

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (design session)

## Context

Groups track everyone's spending, not only mine. An earlier draft kept pairwise debts inside groups and required every pairwise balance to be zero before a member could be removed. That conflicts with simplified settle-ups (A pays C on B's behalf leaves no pair at zero), and with many members it makes settling slow.

## Decision

Inside a group, each member's balance is their net position toward the group (nets sum to 0). `SettlePlanner` suggests transfers by greedily matching the largest creditor with the largest debtor (at most n−1 debtor–creditor pairs per currency, a pair above the amount cap split into capped transfers; computed on demand, never stored). Removing a member requires a zero net. Outside groups, balances are pairwise, and every entry involves exactly me and one other person; anything with three or more people needs a group. Me is in every group.

## Alternatives Considered

### Alternative 1: Strict pairwise debts inside groups
- **Pros**: "X owes me" is always a direct relationship.
- **Cons**: incompatible with debt simplification; many transfers to settle.
- **Why not**: groups could never look settled after simplified payments.

### Alternative 2: Person balances derived from the settle plan (Splitwise style)
- **Pros**: one number per person.
- **Cons**: my balance with X changes when unrelated members add expenses.
- **Why not**: confusing. Instead, groups and people are shown separately, and the person screen lists plan suggestions.

## Consequences

### Positive
- Debt simplification comes for free; groups settle in at most n−1 transfers (unless a net exceeds the per-transfer amount cap).
- Clear rules for removal and for ad-hoc entries (`NeedsGroup`).

### Negative
- Home shows people and groups as separate rows instead of one number per person.

### Risks
- The greedy plan isn't always the minimum. Acceptable; optimal simplification (B1) can replace `SettlePlanner` later.
