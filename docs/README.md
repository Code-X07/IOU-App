# Documentation

A map of everything in `docs/`. If you only read one thing, read the [MVP design spec](superpowers/specs/2026-11-02-iou-mvp-design.md).

## Start here

| Document | What it is for |
|---|---|
| [MVP design spec](superpowers/specs/2026-11-02-iou-mvp-design.md) | The product and technical design for milestones 0–2: data model, balances, groups, currencies, history, backups, import/export, security |
| [Architecture decision records](adr/README.md) | Why the code is shaped the way it is, one decision per file |
| [UI contributor guide](ui-contributor-guide.md) | How the Compose UI talks to the core through the `api` package and the in-memory fake |

## Design

- [MVP design spec](superpowers/specs/2026-11-02-iou-mvp-design.md): the main spec.
- [Multiple payers in the UI](superpowers/specs/2026-11-21-multi-payer-ui-design.md): screen design for expenses paid by several people.
- [M2: what changes for the UI](m2-ui-contract-changes.md): the `api` changes Milestone 2 brought for UI code.

## Architecture decisions

| ADR | Decision |
|---|---|
| [0001](adr/0001-ledger-rows-plus-revision-log.md) | Ledger stored as Room rows plus an append-only revision log |
| [0002](adr/0002-group-net-positions-and-settle-plan.md) | Group balances are member net positions with a greedy settle plan |
| [0003](adr/0003-global-identity-and-person-merge.md) | Stable global "me" identity and person merge as the linking primitive |
| [0004](adr/0004-api-contract-and-fake-for-ui.md) | Core/UI boundary is a pure-Kotlin `api` package plus an in-memory fake |
| [0005](adr/0005-fx-resolved-at-write-time.md) | FX conversion is resolved at write time and stored as effect amounts |
| [0006](adr/0006-single-module-pure-kotlin-packages.md) | Single Gradle module with Android-free domain packages |
| [0007](adr/0007-restore-commit-via-db-pointer.md) | Backup restore commits through an atomic database pointer |
| [0008](adr/0008-shared-pure-write-path-and-read-models.md) | Write path and read models are shared pure packages |
| [0009](adr/0009-alias-edit-tokens-for-unmerge.md) | Each merged alias exposes its own edit token for unmerge |
| [0010](adr/0010-shared-store-with-room-write-through.md) | Both flavors run on one in-memory store; Room is its write-through journal |
| [0011](adr/0011-device-settings-json-file.md) | Device-local settings live in a JSON file, not DataStore |
| [0012](adr/0012-expense-payers-and-contribution-effects.md) | Expenses have payer rows; effects come from per-person contributions |
| [0013](adr/0013-recurring-expenses-as-ledger-records.md) | Recurring expenses are ledger records, posted in the foreground and by a background worker |

New ADRs start from the [template](adr/template.md).

## Implementation plans

Each milestone has an index that links its parts in order.

- [Milestone 0](superpowers/plans/2026-11-02-m0-00-index.md): money math, domain model, the `api` contract, balance engine, validation rules, write path, the fake backend (`v0.0.1`).
- [Milestone 1](superpowers/plans/2026-11-04-m1-00-index.md): the real backend on Room, exchange rates, encrypted backups, CSV export and import, app lock (`v0.1.0`).
- [Milestone 2](superpowers/plans/2026-11-07-m2-00-index.md): multiple payers, recurring expenses, group import, XLSX export (`v0.2.0`).
- One-off plans: [merging the Compose UI (PR #1)](superpowers/plans/2026-11-06-pr1-merge.md), [multiple payers UI](superpowers/plans/2026-11-21-multi-payer-ui.md).

## Testing and verification

- [Manual test checklist](manual-test-checklist.md): on-device checks, run before each release.
- [M2 verification](m2-verification.md): the emulator run of the Milestone 2 screens.

## Reviews

| Review | Scope |
|---|---|
| [M0 implementation review](reviews/2026-11-04-m0-implementation-review.md) | Milestone 0 code |
| [M0/M1 implementation review](reviews/2026-11-06-m1-implementation-review.md) | Milestone 1 code |
| [PR #1 integration review](reviews/2026-11-06-pr1-integration-review.md) | Merging the Compose UI |
| [M2 design review](reviews/2026-11-07-m2-design-review.md) | The Milestone 2 design |
| [M2 plan review](reviews/2026-11-08-m2-plan-review.md) | The Milestone 2 plans |
| [Implementation review (2026-11-21)](reviews/2026-11-21-current-implementation-review.md) | The whole app after `v0.2.0` |
| [Security audit](reviews/2026-11-21-security-audit.md) | Threats, findings and their triage (fixed, deferred, accepted) |

Each review ends with how its findings were resolved.
