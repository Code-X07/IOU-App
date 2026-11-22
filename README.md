# IOU

An Android app for tracking shared expenses and IOUs in a friend group. It replaces "who owes whom" chat threads with exact, auditable balances.

> **Status:** Milestones 0–2 are done. Get the APK from the [latest release](https://github.com/Code-X07/IOU-App/releases/latest).

## What it does (MVP)

- **Running balances** with each person, regardless of which bank account money moved through.
- **Groups** for trips, flats and dinners, with equal, percentage, shares or exact splits, and suggested settle-up transfers.
- **Multiple currencies**, with exchange rates (ECB via Frankfurter) fixed at the time of entry; per group, convert to one currency or keep each currency separate.
- **Full audit history:** every edit is recorded with a diff and can be reverted.
- **Backups and export:** encrypted JSON backups, CSV and XLSX export, CSV import.
- **Privacy:** local-first and offline; app lock; optional screen protection. The app never moves money.

Later phases add pay/request helpers (EPC QR, Revolut links), automatic detection of repayments, and sync between friends.

## Repository layout

| Path | Contents |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose, Room) |
| `docs/` | Spec, plans, ADRs, reviews and checklists |
| `.github/` | CI workflows |

## Docs

- [Documentation index](docs/README.md): every spec, ADR, plan and review in one place
- [MVP design spec (milestones 0–2)](docs/superpowers/specs/2026-11-02-iou-mvp-design.md)
- [Multiple payers UI design](docs/superpowers/specs/2026-11-21-multi-payer-ui-design.md)
- [UI contributor guide](docs/ui-contributor-guide.md)
- [Manual test checklist](docs/manual-test-checklist.md), run before each release
- [Architecture decision records](docs/adr/README.md)
- [Reviews](docs/README.md#reviews): implementation, design, plan and security reviews

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

This is a personal project, so maintenance will be slow. Outside contributions aren't expected, but they are welcome: open an issue or a pull request.

Two contributors: core (domain, data, engine) and UI/UX (screens, ViewModels). The boundary is the `api` package plus an in-memory fake, described in spec §6.

## License

GPL-3.0. See [LICENSE](LICENSE).
