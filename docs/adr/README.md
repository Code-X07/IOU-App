# Architecture Decision Records

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-ledger-rows-plus-revision-log.md) | Ledger stored as Room rows plus an append-only revision log | accepted | 2026-11-02 |
| [0002](0002-group-net-positions-and-settle-plan.md) | Group balances are member net positions with a greedy settle plan | accepted | 2026-11-02 |
| [0003](0003-global-identity-and-person-merge.md) | Stable global "me" identity and person merge as the linking primitive | accepted | 2026-11-02 |
| [0004](0004-api-contract-and-fake-for-ui.md) | Core/UI boundary is a pure-Kotlin `api` package plus an in-memory fake | accepted | 2026-11-02 |
| [0005](0005-fx-resolved-at-write-time.md) | FX conversion is resolved at write time and stored as effect amounts | accepted | 2026-11-02 |
| [0006](0006-single-module-pure-kotlin-packages.md) | Single Gradle module with Android-free domain packages | accepted | 2026-11-02 |
| [0007](0007-restore-commit-via-db-pointer.md) | Backup restore commits through an atomic database pointer | accepted | 2026-11-02 |
| [0008](0008-shared-pure-write-path-and-read-models.md) | Write path and read models are shared pure packages (`ledger.ops`, `ledger.views`) | accepted | 2026-11-02 |
| [0009](0009-alias-edit-tokens-for-unmerge.md) | Each merged alias exposes its own edit token for unmerge | accepted | 2026-11-02 |
| [0010](0010-shared-store-with-room-write-through.md) | Both flavors run on one in-memory store; Room is its write-through journal | accepted | 2026-11-04 |
| [0011](0011-device-settings-json-file.md) | Device-local settings live in a JSON file, not DataStore | accepted | 2026-11-04 |

Full design: [MVP design spec](../superpowers/specs/2026-11-02-iou-mvp-design.md). For a new ADR, copy [template.md](template.md).
