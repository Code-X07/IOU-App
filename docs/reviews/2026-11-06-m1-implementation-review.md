# Current implementation review — M0/M1

Date: 2026-11-06 (Asia/Calcutta)  
Repository: `D:\Workspace\IOU-App`  
Reviewed commit: `bbd55f4df64ea2c1d2f14e8093388e0dcfbfd92a` (`main`, post-v0.1.0 spec sync)

## Scope and assessment

Onboarding covered `AGENTS.md`, `HANDOFF.md`, `state.md`, the current [MVP design](../superpowers/specs/2026-11-02-iou-mvp-design.md), the [M1 plan index](../superpowers/plans/2026-11-04-m1-00-index.md), relevant implementation plans and ADRs, and both knowledge graphs. The code-review graph matched the reviewed commit. Its cumulative M1 change analysis hit the 500-function cap; scoped queries and direct source inspection supplied the detail, rather than treating graph results as exhaustive.

Reviewed the shared ledger/model/rules/ops/views, money and splits, store and Room persistence, FX, backup/restore, CSV import/export and undo, app-security wiring, API contracts, and build/release configuration. The current spec and recorded decisions take precedence over historical plan prose. This is a review of shipped M0/M1 core functionality, not the separate UI branch or unfinished M2 features.

The implementation substantially follows the approved architecture: one shared store and write path, journal persistence before publication, generation-based stale-draft protection, pointer-based restore, and shared rules for fake and real backends. Existing tests and lint pass. The findings below concern concrete boundary-validation and API behavior gaps; passing tests do not cover these scenarios. No broad refactor is recommended.

## Severity summary

| Tier | Meaning | Count | Findings |
| --- | --- | ---: | --- |
| Critical | Immediate severe data loss or security exposure in ordinary use | 0 | None established |
| Important | Major practical correctness failure or a broadly blocked core workflow | 0 | None established |
| Medium | Reproducible functional/data-integrity defect with bounded impact | 3 | F1–F3 |
| Low | Limited validation, projection, lifecycle, or integration-contract defect | 6 | F4–F9 |
| Very low | Minor actionable issue with negligible practical impact | 0 | None retained |

## Medium

### F1 — Import accepts an incomplete archive despite contradictory manifest counts

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/io/ImportReader.kt:79–98`, and `ImportFile.rows()` at line 20. Manifest production: `io/LedgerExport.kt:43–58` under the same source root.

**Trigger:** Export a valid non-group ledger containing one transfer. Remove `transfers.csv` while retaining the unchanged manifest, which still declares one transfer. Import into a ledger containing the same me identity but no transfers.

**Observed:** An executable probe through `RealImportExportApi` returned a preview with zero invalid rows, committed successfully, and saved zero transfers. The reader parses `manifest.tables` but never compares its counts with the parsed tables or requires the expected CSV files. Missing tables become empty lists. Deleting data rows without updating the manifest has the same unchecked boundary.

**Impact:** An incomplete edited/repacked archive is accepted as an ordinary import, producing incomplete balances without an integrity warning. This does not delete transfers already present locally; the demonstrated loss is missing incoming data. Spec §7.2 explicitly supplies row counts so import can check completeness, and §7.5 step 2 includes those counts in mapping.

**Suggested correction:** Require the schema's known tables and validate actual data-row counts against the manifest before decoding. Distinguish an intentionally empty table from a missing table. Continue handling unknown unique ZIP entries according to the existing compatibility policy. Test a missing nonempty table, a missing empty table, and a mismatched count.

### F2 — A backup revision can target a different entity when reverted

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/data/SnapshotCodec.kt:94–98`; `data/BackupFormat.kt:75–90`; `ledger/ops/RevertOps.kt:29–34,48–53` under the same source root.

**Trigger:** In an otherwise valid plaintext backup, leave a person revision's `entityType`, `entityId`, and version identifying person A, but replace its `snapshotJson` with a well-formed snapshot of person B. Read the backup, then request a revert of A to that revision.

**Observed:** `BackupFormat.read` returned `Ok`. Running the normal revert op against the resulting content returned `Ok`, but its change targeted B: the probe requested `seed-5` and produced a change to `seed-9`. Revision metadata and snapshot are decoded independently; validation checks the live ledger state, not their agreement. `businessFieldsOf` preserves the snapshot's ID while replacing its metadata with the selected current row's metadata. A mismatched snapshot type can also reach an invalid cast.

**Impact:** Accepted corrupt history can make a later user-directed revert modify the wrong record. This violates the revision/entity relationship and identity-preserving revert in spec §5.3, and the backup validation boundary in §7.1. Severity is Medium because it requires malformed input; no ordinary backup produced by the app was shown to generate this mismatch. This is not a bypass of encrypted backup authentication.

**Suggested correction:** Before accepting a backup, require each revision's snapshot type, ID and version to match its metadata. Defensively ensure a revert applies only to the requested current identity. Add same-type/different-ID and different-type cases, verifying rejection before any database replacement. The probe exercised format validation and the pure revert write plan, not a device restore.

### F3 — Settlement drafts use the UTC calendar date instead of the device's local date

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/data/RealBackend.kt:133`; `store/LedgerStore.kt:71`; `store/StoreApis.kt:152–155` under the same source root.

**Trigger:** Request a person or group settlement around a local midnight when the UTC calendar date differs. At `2026-11-05T19:00:00Z`, the date in Asia/Kolkata is 2026-11-06.

**Observed:** With that instant and the UTC clock used by production wiring, `LedgerStore.today()` returns 2026-11-05. Both settlement APIs use it for `occurredOn`.

**Impact:** Accepting the prefilled draft records a repayment on the previous day in this example, affecting activity/date-filtered reports and potentially the date chosen for a new FX quote. Spec §3.1 treats `occurredOn` as a local entry date; §4.6 supplies prefilled repayment drafts. The implementation follows the plan's `Clock.systemUTC()` wiring, so this is also a plan-level behavior defect, rather than an accidental departure from its code block.

**Suggested correction:** Separate instant generation from local-calendar-date selection, using the current device zone for settlement defaults. Keep persisted timestamps as instants. Cover both sides of UTC midnight and device-zone changes. This finding was verified with a fixed-clock JVM probe and the production wiring; no device clock was changed.

## Low

### F4 — An unchanged post-merge split preview lists the same person twice

**Location:** `android/app/src/main/kotlin/io/github/codex07/iou/ledger/views/EntryViews.kt:193–194`.

**Trigger:** Save a three-person EQUAL expense, merge two participants, and preview a note-only edit from `EntryDetail.editDraft`.

**Observed:** The probe correctly reported `recomputed=false`, but `SplitPreview.shares` contained two separate rows for the same canonical person. For a three-cent example it returned `[Me:1, Alex:1, Alex:1]`, while `storedShares` returned `[Me:1, Alex:2]`. The proposed-share projection resolves names/IDs per stored row without aggregating; the stored-share projection aggregates correctly.

**Impact:** The comparison is inconsistent even though the split is unchanged. A UI following the ready-to-render API contract can display duplicate participants or an apparent split change. Stored balances and the save operation remain correct. Spec §4.4 and §4.1 require combined canonical shares while retaining historical rows in storage.

**Suggested correction:** Aggregate preview shares by canonical person, including the appropriate combined inputs, using the same projection rules as stored shares. Preserve the underlying historical share rows. Test note-only previews after merges for equal and weighted splits.

### F5 — Repeated identical participant IDs bypass `DuplicateParticipant`

**Location:** `android/app/src/main/kotlin/io/github/codex07/iou/ledger/ops/EntryOps.kt:167–177`; aggregation at lines 131–143.

**Trigger:** Save an expense whose draft contains `[me, Alex, Alex]`, with the exact same raw Alex ID twice.

**Observed:** The public write op returned `Ok`; a €9 EQUAL draft became €4.50 each for me and Alex. `combineInputs` collapses repeated identical IDs before `SplitResolver` can reject duplicates. Weighted input values are added in the same place.

**Impact:** A malformed draft is silently normalized instead of returning the documented `DuplicateParticipant` error (spec §4.4). This is a limited API validation gap, not a claim that the future picker necessarily permits duplicate selections.

**Suggested correction:** Reject repeated raw participant IDs before canonical aggregation. Continue combining distinct person records that resolve to one person after a merge; that behavior is intentional and must remain. Cover both save and preview paths.

### F6 — Abandoned import previews retain their parsed files for the entire process

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/data/RealImportExportApi.kt:66,104–106,119–121,171`; `api/ImportExportApi.kt` under the same source root.

**Trigger:** Parse an import, leave its preview without committing, and choose another file. The same issue applies when the UI drops a preview after ledger replacement.

**Observed:** Three abandoned parses left three entries in the backend's `sessions` map. Each retains decoded file data and an analysis referencing a ledger snapshot. Removal exists only for a successful commit or declined foreign identity; there is no discard API, replacement cleanup, or eviction bound. Dropping the UI's preview reference cannot release these objects.

**Impact:** Ordinary cancel/retry usage accumulates memory, especially for large files or a long-running process. Exhaustion was not stress-tested, so it is not claimed as an observed crash. Spec §6.2/§7.5 permits in-memory sessions, but the API needs a way to end their lifetime.

**Suggested correction:** Add explicit discard/cancel semantics and call them when leaving a preview, with cleanup of obsolete generations and a reasonable retained-session bound. Preserve active-session behavior. Test cancellation and ledger replacement cleanup without allocating giant fixtures.

### F7 — Mixed history exposes rows without supplying their current edit token

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/api/ReadModels.kt:176–181,265–277`; `ledger/views/LedgerViews.kt:163–165,235,253`; `ledger/engine/IdentityResolver.kt:54–56` under the same source root. Contract guidance: `docs/ui-contributor-guide.md:53`.

Group history contains membership revisions, but `MemberView` has neither the membership row's identity/token nor per-alias membership tokens. Person history contains deleted-handle revisions, but deleted handles are filtered from the handle views that normally provide their tokens. `RevisionView` has no current token either.

**Impact:** The documented instruction to call `revertTo` using the selected row's token cannot be followed for these subjects. The group's or person's token is a different row's version. `EditToken` is publicly constructible, so a UI could infer a version from history and combine it with the detail generation, but that workaround is absent from the intended read-model contract. This is a UI integration gap under spec §5.3/§6.2, not proof that every revert is impossible.

**Suggested correction:** Supply a current token for each history subject, or provide a row-token lookup. Cover a removed membership and a deleted handle. Verify the UI never substitutes the owner row's token. This finding is based on source/API inspection.

### F8 — Duplicate primary keys in backups are silently collapsed before validation

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/data/LedgerRows.kt:40–46,63–69`; `data/BackupFormat.kt:82–90` under the same source root.

**Trigger:** Duplicate a person row in a plaintext backup, place an invalid empty display name in the earlier copy, and retain a valid later copy with the same ID.

**Observed:** Backup validation returned `Ok` for eight source people rows that mapped to seven people. `associate` retains only the final value per key, so the earlier invalid row never reaches field validation. Restore receives the original lists; upsert tables likewise overwrite earlier copies, while duplicate keys in insert-only tables can instead fail during building.

**Impact:** A malformed backup is accepted after silently discarding conflicting rows, contrary to the complete-file validation expected in spec §7.1. This is separate from F2: even perfectly matched revision snapshots do not establish table-key uniqueness. It requires malformed input and is not an ordinary app-generated backup failure.

**Suggested correction:** Validate primary/composite-key uniqueness for all backup tables before constructing maps or starting a restore. Test an invalid earlier duplicate as well as duplicates in insert-only tables, returning `CorruptFile` consistently.

### F9 — The rate worker does not apply its retry policy to cache-write exceptions

**Locations:** `android/app/src/real/kotlin/io/github/codex07/iou/data/RateRefreshWorker.kt:30–32`; `android/app/src/main/kotlin/io/github/codex07/iou/fx/FxService.kt:85,96–98`. Existing evidence: `android/app/src/test/kotlin/io/github/codex07/iou/fx/FxServiceTest.kt:60`.

**Trigger:** A refresh fetches rates but persisting them raises a recoverable storage exception. The existing FX test explicitly verifies that a cache `IOException` propagates from `refresh`.

**Impact:** `doWork` only returns `Result.retry()` when `refreshRates()` returns false. A thrown exception leaves that branch unreachable, so this failure does not request the promised exponential retry. Spec §8's worker row says “Any” failure receives backoff; the ordinary network-unavailable path already does. Android's documented backoff path is an explicit `Result.retry()` return. [Android Developers: retry and backoff](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#retry_and_backoff_policy).

**Suggested correction:** Translate recoverable refresh/storage failures into `Result.retry()` at the worker boundary, while preserving coroutine cancellation. Add a worker-level failure case. The source path and existing FX test were checked; WorkManager scheduling was not exercised on a device. This finding does not claim that periodic work is permanently disabled.

## Obvious security review

No separate obvious exploitable security flaw was established. The bounded review checked backup encryption/header authentication and KDF limits, import ZIP handling and text escaping, SAF access, cleartext-network policy, lock/screen-protection wiring, backup exclusions, debug-only components, and secret handling in release configuration. F2/F8 are file-validation integrity findings, not evidence that AES-GCM authentication can be bypassed. No signing secrets were read.

This was not a penetration test, dependency/CVE audit, cryptographic audit, or fresh release-APK policy inspection. The separate security session should retain those responsibilities. The explicitly accepted unreadable-settings fallback, including lock-off defaults, is recorded in ADR-0011/state.md and was not presented as a new discovery.

## Compliance boundaries and existing carries

- Working real-flavor groups and M1 import undo follow the M1 index overrides; the older AC14 restriction is historical.
- CSV import of group rows, XLSX export/FastExcel validation, and the full group export→import round-trip remain M2 scope. They are not ranked as missing M1 functionality.
- The friend's screens/ViewModels and on-device SAF/biometric walkthroughs, plus low-end-phone KDF timing, remain explicit carries. The placeholder UI is not reviewed as finished product UI.
- Shared store/Room write-through and file-based device settings follow ADR-0010/0011. Accepted process-death versus power-loss limits and post-commit restore restart behavior were not relabeled as defects.
- Historical M0 findings were consulted to avoid duplicate reporting. The deleted-default guard, full fake ledger-content replacement, generation-aware list projections, stored-split validation and interpolation-aware purity scanner are now present. The accepted `approxHome` overflow-to-null and placeholder `collectAsState` carries were excluded.
- Where a finding is also present in generated plan code, remediation should follow the repository's plan/reference maintenance convention. This review changes neither implementation nor plans.

## Verification and limits

| Check | Result |
| --- | --- |
| Fresh `testFakeDebugUnitTest` | 380 tests, 68 suites; 0 failures, errors or skips |
| Fresh `testRealDebugUnitTest` | 380 tests, 68 suites; 0 failures, errors or skips |
| `lintFakeDebug` | Passed; 0 errors, 32 warnings |
| `lintRealDebug` | Passed; 0 errors, 32 warnings |
| Isolated compiled-code probes | Confirmed F1–F6 and F8 as described; F7 is source/API inspection, F9 uses source plus an existing test |

The initial test invocation was up-to-date. A temporary Gradle init script outside the repository then forced only the test tasks to execute again; the counts above come from those fresh XML results. Temporary Kotlin probes used the current compiled application/test classpath and existing fixtures, outside the repository. They did not install regression tests or alter application sources. The two flavor runs cover overlapping suites, not 760 distinct tests.

No emulator/user data, app settings, credentials, remote branches, or releases were modified. No fresh APK assembly, signing, physical-device check, or manual UI test was performed. The tracked tree was clean before writing this report. The report is the only repository change; no code, tests, configuration, specs, or plan files were edited, and nothing was committed or pushed.

Recommended order: address the three Medium findings first, then the preview/validation and lifecycle/API gaps. Keep each fix targeted and add the corresponding regression case rather than broadening the project architecture.

## Resolution (2026-11-06)

All nine findings were re-verified against the source at `bbd55f4` and fixed on `main`, each with a regression test:

| Finding | Fix |
| --- | --- |
| F1 | `ImportReader` requires every schema table, even an empty one, and checks each table's data rows against the manifest count (`CorruptFile`). |
| F2 | `BackupFormat.read` rejects a revision whose snapshot type, ID or version differs from its metadata; `RevertOps.revertTo` and `draftFromVersion` treat such a snapshot as missing (`NotFound`). |
| F3 | `LedgerStore.today()` uses the device's current zone (`ZoneId.systemDefault()` in the real and fake wiring, asked on every call); instants stay UTC. |
| F4 | An unchanged split preview shows the stored shares combined per canonical person, equal to `storedShares`. |
| F5 | The same person record twice in a draft is `DuplicateParticipant` on save and preview; distinct records of a merged person still combine. |
| F6 | New `ImportExportApi.discardImport`; the real flavor keeps one open import session (a new parse ends earlier ones) and drops a session answered with `LedgerReplaced`. |
| F7 | `RevisionView.token` carries the current token of the revision's own row (memberships and deleted handles included). |
| F8 | Backups with duplicate primary keys in any table are `CorruptFile` before mapping. |
| F9 | `RealBackend.refreshRates` returns false (worker `Result.retry()`) for any non-cancellation failure, including a cache write failure. |

Both flavors pass 392 tests in 68 suites (380 before, plus 12 new). The spec and `docs/ui-contributor-guide.md` describe the new behaviour.
