# IOU

An Android app for tracking shared expenses and IOUs in a friend group. It replaces "who owes whom" threads in WhatsApp with exact, auditable balances.

> **Status:** Milestone 1 implementation complete (v0.1.0). The real flavor is a working ledger on Room, with groups, history and revert, merges, FX, encrypted backups, CSV export and import, app lock and screen protection. The screens come from the UI contributor. Importing group rows and XLSX export come in Milestone 2.

## What it does (MVP)

- **Running balances** with each person, regardless of which bank account money moved through.
- **Groups** for trips, flats and dinners, with equal, percentage, shares or exact splits, and suggested settle-up transfers.
- **Multiple currencies**, with exchange rates (ECB via Frankfurter) fixed at the time of entry; per group, convert to one currency or keep each currency separate.
- **Full audit history:** every edit is recorded with a diff and can be reverted.
- **Backups and export:** encrypted JSON backups, CSV and XLSX export.
- **Privacy:** local-first and offline; app lock; optional screen protection. The app never moves money.

Later phases add pay/request helpers (EPC QR, Revolut links), automatic detection of repayments, and sync between friends.

## Repository layout

| Path | Contents |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose, Room) |
| `docs/` | Specs, plans and checklists |
| `.github/` | CI workflows |

## Docs

- [MVP design spec (milestones 0–2)](docs/superpowers/specs/2026-11-02-iou-mvp-design.md)
- [UI contributor guide](docs/ui-contributor-guide.md)
- [Manual test checklist](docs/manual-test-checklist.md), run before each release

## Tech

Kotlin, Jetpack Compose and Material 3, Room, Hilt, Coroutines/Flow, WorkManager, Ktor. Min SDK 30.

## Contributing

Two contributors: core (domain, data, engine) and UI/UX (screens, ViewModels). The boundary is the `api` package plus an in-memory fake, described in spec §6.
