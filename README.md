# IOU

An Android app for tracking shared expenses and IOUs in a friend group. It replaces "who owes whom" threads in WhatsApp with exact, auditable balances.

> **Status:** Milestone 1 is complete (v0.1.0), and every finding of the [M1 implementation review](docs/reviews/2026-11-06-m1-implementation-review.md) has been fixed since. The real flavor is a working ledger on Room, with groups, history and revert, merges, FX, encrypted backups, CSV export and import, app lock and screen protection. The Compose UI is merged into `main` (PR #2, which supersedes #1), with every finding of the [integration review](docs/reviews/2026-11-06-pr1-integration-review.md) and its targeted re-review resolved, so both flavors now run with the full UI. Current limits: XLSX export and group-row CSV import arrive in Milestone 2, and some on-device checks are still open (section 8 of the [manual test checklist](docs/manual-test-checklist.md)): biometric paths, screenshots with screen protection, the interrupted-restore notice and the fake-flavor walkthrough. There is no Compose instrumentation suite yet. Milestone 2 is next.

## What it does (MVP)

- **Running balances** with each person, regardless of which bank account money moved through.
- **Groups** for trips, flats and dinners, with equal, percentage, shares or exact splits, and suggested settle-up transfers.
- **Multiple currencies**, with exchange rates (ECB via Frankfurter) fixed at the time of entry; per group, convert to one currency or keep each currency separate.
- **Full audit history:** every edit is recorded with a diff and can be reverted.
- **Backups and export:** encrypted JSON backups, CSV export and import (XLSX export in Milestone 2).
- **Privacy:** local-first and offline; app lock; optional screen protection. The app never moves money.

Later phases add pay/request helpers (EPC QR, Revolut links), automatic detection of repayments, and sync between friends.

## Repository layout

| Path | Contents |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose, Room) |
| `docs/` | Spec, plans, ADRs, reviews and checklists |
| `.github/` | CI workflows |

## Docs

- [MVP design spec (milestones 0–2)](docs/superpowers/specs/2026-11-02-iou-mvp-design.md)
- [UI contributor guide](docs/ui-contributor-guide.md)
- [Manual test checklist](docs/manual-test-checklist.md), run before each release
- [Architecture decision records](docs/adr/README.md)
- Implementation reviews: [M0](docs/reviews/2026-11-04-m0-implementation-review.md), [M1](docs/reviews/2026-11-06-m1-implementation-review.md), [PR #1 integration](docs/reviews/2026-11-06-pr1-integration-review.md)

## Tech

Kotlin, Jetpack Compose and Material 3, Room, Hilt, Coroutines/Flow, WorkManager, Ktor. Min SDK 30.

## Building

Needs JDK 21. From `android/`:

```bash
./gradlew installFakeDebug        # in-memory fake with demo data, for UI work
./gradlew installRealDebug        # the real app on Room
./gradlew testFakeDebugUnitTest testRealDebugUnitTest
./gradlew lintFakeDebug lintRealDebug
```

The two flavors install side by side (the fake has the `.fake` application ID suffix). Release builds are signed in CI from a version tag.

## Contributing

Two contributors: core (domain, data, engine) and UI/UX (screens, ViewModels). The boundary is the `api` package plus an in-memory fake, described in spec §6.
