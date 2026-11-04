# M0 implementation review

Date: 2026-11-04  
Reviewed commit: `cb2d991348d545aadd87a287fa533023465ff341` (`v0.0.1`)

## Scope and assessment

Reviewed the current implementation for spec compliance, code quality, practical edge cases, and obvious security flaws. The baseline is the [MVP design](../superpowers/specs/2026-11-02-iou-mvp-design.md), the M0 implementation plans and their explicit decisions, and ADRs. Coverage includes money and ledger models, calculations, validation, writes/reverts, read projections, fake APIs and contract tests, application wiring, manifests, and build/release workflows.

The shared pure write/read architecture is coherent, and the existing tests and lint checks pass. One current write-path defect merits a targeted fix. Four lower-priority findings concern fake restore fidelity, replacement notifications, future use of the existing validator, and the CI guard. No Critical or Important finding was established. This is a source review, not proof that all behavior is correct.

The real flavor's empty in-memory backend, simulated file operations and fixed FX rates are explicit M0 decisions. Missing Room persistence, real encrypted backups, import implementation, and device lock enforcement are not counted as defects in this review. The placeholder UI is intentionally awaiting replacement. Individual zero SHARES weights are also explicitly permitted by the Part 2 plan despite the original spec wording.

## Severity summary

| Tier | Meaning | Findings |
| --- | --- | --- |
| Critical | Immediate severe data-loss or security exposure | None established |
| Important | Major practical correctness failure or core workflow blocked | None established |
| Medium | Reproducible functional defect with a bounded impact | F1 |
| Low | Limited contract mismatch, validation gap, or regression protection weakness | F2–F5 |
| Very low | Minor actionable issue with negligible practical impact | None retained |

## Medium

### F1 — Reverting a deleted payment handle can remove the live default

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/ledger/ops/RevertOps.kt:33–41`; `ledger/ops/PeopleOps.kt:123–125,157–162` under the same source root.

**Reproduction:** For one person, create handle A as the default of a type. Create handle B as the default of that same type; this clears A's default flag and records a revision. Delete A. Using A's current edit token, revert A to its original default-bearing revision.

The revert preserves A's current `deletedAt`, but restores `isDefault=true`. Because that flag changed, it calls `clearedDefaults`, which clears live handle B. A remains deleted and is excluded by `IdentityResolver.handlesOf`, so neither handle is now an effective default. The write succeeds: the validator excludes deleted A from field validation, has no handle-specific edit prohibition, and permits the absence of a default. Updating a deleted handle with `isDefault=true` can produce the same side effect.

**Impact:** Editing historical/deleted data silently changes the user's live payment-handle preference and appends a misleading “Default moved” revision. This conflicts with the default-selection behavior in spec §3.2 and the preservation of the current deleted state during revert in §5.3.

**Suggested direction:** Only displace live defaults when the resulting handle is live. Alternatively, explicitly reject unsupported edits to deleted handles. Cover the sequence above, an update of a deleted handle, and a normal live-handle revert. This finding was verified by tracing the complete write/validation path; no new executable regression test was added during this review.

## Low

### F2 — Undoing a fake restore restores only part of the ledger state

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/fake/InMemoryLedger.kt:31–39`; `fake/FakeServiceApis.kt:86–101` under the same source root.

`preRestore` stores only `LedgerState` and revisions. `importBatches` and ledger-scoped settings are outside that pair and are never swapped by undo.

**Reproduction:** Start with EUR as home currency, create and restore a simulated backup, then switch home currency to USD and commit a simulated import. Undo the restore. Entity rows and revisions return to the captured state, but the home currency remains USD and the later import batch remains visible. A second undo cannot recover the matching settings/history for each side either.

**Impact:** The fake presents an internally inconsistent approximation of the whole-ledger replacement promised by spec §3.3/§7.1. UI testing of settings and import-history behavior across restore can therefore give misleading results. Severity is Low because these operations are explicitly simulated and no real backup file is lost.

**Suggested direction:** Capture and swap all simulated ledger-scoped state, including import batches, home currency and backup-encryption preference. Preserve device-local lock settings, screen protection and last-backup time. Keep the existing deliberate behavior of restoring the current data rather than actual file contents.

### F3 — Equal list values suppress the promised replacement notification

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/fake/FakeLedgerApis.kt:64–65`; `api/ReadModels.kt:63–71` under the same source root.

`observePeople` maps a ledger snapshot to `List<PersonSummary>` and then applies `distinctUntilChanged`. That summary has no generation field. Restore changes the generation but preserves current fake data, so an existing subscriber receives no replacement emission when the list compares equal. An empty people list is a minimal example. Other list projections using the same pattern deserve the same check.

**Impact:** Spec §6.2 explicitly promises that all read flows re-emit after replacement. That guarantee is weaker in the fake than documented. The dedicated generation flow does emit, and the UI is required to listen to it; this is therefore a contract mismatch rather than evidence that stale writes bypass protection.

**Suggested direction:** Include generation in the distinctness comparison before projecting the final list, or explicitly narrow the documented contract. Add a contract case that restores unchanged data while a list collector remains active.

### F4 — The shared validator does not validate stored split inputs

**Locations:** `android/app/src/main/kotlin/io/github/codex07/iou/ledger/rules/FieldRules.kt:88–105`; `ledger/rules/LedgerValidator.kt:39–43` under the same source root.

The stored-share checks validate participants, nonnegative share amounts and the sum of those amounts. They never inspect `ExpenseShare.inputValue` according to `splitType`.

**Example:** An otherwise valid €10 PERCENT expense with two €5 stored shares and inputs of 10,000 basis points each passes these checks. The shares total €10, but the inputs total 200%. Missing or negative SHARES inputs are likewise unchecked if the stored amounts are valid. The `WriteOp.Import` path in the existing validator uses this same field validation; no later invariant checks those inputs.

**Impact and scope:** Spec §7.4 requires imported stored inputs to be valid without recomputing the saved shares. The normal create/recompute path already uses `SplitResolver`, and the fake importer does not install incoming rows, so this is a latent gap in an existing shared component, not a currently exploitable file-import bug. It should be addressed before M1 relies on the validator for incoming data.

**Suggested direction:** Validate inputs on the original stored rows, with the accepted zero-weight policy, while preserving stored allocations. Do not recompute leftover cents or reject an unchanged historical split merely because merged canonical participants would have a combined weight above the normal input limit. Add direct validator cases for malformed stored inputs and valid preserved post-merge shares.

### F5 — The floating-point CI guard ignores executable string interpolation

**Location:** `android/app/src/test/kotlin/io/github/codex07/iou/DomainPurityTest.kt:99–103`.

`SourceScan.usesFloatingPoint` removes ordinary quoted strings before checking for floating-point operations. Kotlin expressions inside those strings execute, but the guard removes them too. For example, a checked source line containing `"${amount.toDouble()}"` is reduced to an empty string literal and is accepted.

**Impact:** A common money-formatting regression can evade the CI enforcement required by spec §11. No current incorrect floating-point calculation was established; this finding concerns the guard's protection against later changes.

**Suggested direction:** Preserve and scan interpolation expressions, preferably using token-aware handling, and add a regression case for an ordinary interpolated string. Do not flag literal prose that merely mentions `Double` or decimal amounts.

## Obvious security review

No obvious exploitable security flaw was established in the reviewed M0 code and build/release configuration. No signing secrets were read. The fake's in-memory passphrase bookkeeping is an explicit simulation and must not be reused as the real backup implementation. Real file parsing, cryptography, restore recovery, and device lock behavior remain subjects for M1 and the separate security audit. This review did not perform dependency vulnerability analysis, penetration testing, or release-signing verification.

## Verification and limits

- Fresh run: `android/gradlew.bat testFakeDebugUnitTest --rerun-tasks` — successful; XML results report **201 tests, 0 failures, 0 errors, 0 skipped**, across 34 suites.
- `android/gradlew.bat testFakeDebugUnitTest lintFakeDebug` — successful. The initial unit-test task was up-to-date, so the separate fresh run above was performed. Lint reports **20 warnings and no errors**; a passing task is not a warning-free result.
- No source or test code was changed, and no new regression tests were installed. Finding scenarios above are source-level traces unless expressly stated otherwise. No emulator or physical-device validation was performed in this review.
- Code-review-graph was consulted first for change/review context and affected flows. HEAD had no pending implementation diff. Although graph metadata matches the reviewed commit, file-impact queries returned incomplete coverage and no useful flow results; those empty results were not treated as proof of safety. Direct source/spec inspection supplied the missing evidence. The persisted Graphify graph was available from onboarding; its launcher remains unavailable.
- Extremely large aggregate-balance behavior, theoretical arithmetic exhaustion, and cosmetic placeholder-UI issues were excluded from ranked findings to keep the review focused on practical problems.

Recommended order: fix F1, tighten fake restore/notification contracts (F2–F3), close F4 before real import integration, and improve the CI guard (F5). Mirror any later implementation changes into the corresponding plan blocks as required by the repository's workflow.
