# PR #1 integration review: `feat/compose-ui` into `main`

**Review date:** 2026-11-06 (Asia/Calcutta)  
**Decision:** **Not ready to merge.** Resolve the build/integration blockers and behavioral findings below, then validate the final combined commit. Neither the remote PR nor the original application checkout was modified by this review.

## Reviewed branches and scope

| Item | Reviewed value |
| --- | --- |
| PR | [#1 — native Compose UI](https://github.com/Code-X07/IOU-App/pull/1) |
| Actual current `main` | `38edf45104b900fb5650aaa2a5ef12683a9bf77f` |
| PR head | `c49eb682387e298bbdf8527aca9846765070bb3f` |
| Common ancestor | `cb2d991348d545aadd87a287fa533023465ff341` |
| Divergence | 44 commits unique to `main`; 4 unique to the UI branch |
| UI contribution | 67 changed files, 6,247 insertions and 27 deletions relative to the common ancestor |
| GitHub status | Draft; `CONFLICTING` / `DIRTY`; two failed `build` checks; no submitted reviews |
| Branch protection | `main` reports `protected=false`, with no required status checks |
| Isolated clone | `temp/pr1-integration-review-20261106`, branch `review/pr1-merge` |

The PR API's `baseRefOid` initially reported `69c765b...`, an older base snapshot. This review used the fetched remote `main` at `38edf451...`, confirmed by the branch API, rather than assuming that older value was current.

The PR description still describes Phases 1–3 and 244 tests, whereas its head also includes Phases 4–6. Its older CI results are not evidence that the current merged application works. Current main's [CI run](https://github.com/Code-X07/IOU-App/actions/runs/42115902365) is successful; the reviewed [PR CI run](https://github.com/Code-X07/IOU-App/actions/runs/41956071299) fails lint.

Both branch tips and the draft/conflict/failed-check status were rechecked at the end of the review and were unchanged.

Reviewed the merge conflicts, changed API contracts, dependency and manifest integration, UI-to-core writes, forms/FX/splits/settlements, history/revert, navigation lifetimes, settings/security, real versus fake bindings, backup/import/export/restore, CI, tests, and documentation. Three Terra reviewers covered independent areas; the primary agent checked the combined findings and ran the integration experiment. This is a review and readiness report, not a merge or an implementation of the proposed fixes.

The code-review graph was built only inside the clone: 222 files, 2,267 nodes, 29,690 edges. Change detection identified 418 changed functions/classes. Its minimal build had no indexed execution flows, and its `tests_for` result did not identify the existing ViewModel test coverage, so those outputs were not treated as proof of absent tests or absent impact. Focused source inspection and executable checks supplied the missing coverage.

## Evidence and test results

The experiment proceeded in three explicitly different states:

1. **Trial merge:** retained both lifecycle dependency sets in `app/build.gradle.kts`, and selected the PR's `MainActivity` so its UI could compile against current main. This is a diagnostic conflict resolution, not approval of its security policy.
2. **Compile adaptation:** added only explicit `FileAccessFailed` and `RestoreFailed` branches to the UI error mapper in the clone. No behavioral findings were fixed.
3. **Test adaptation:** replaced removed `InMemoryLedger()` test fixtures with `FakeApis.store()` in three test files. Added seven focused regression probes, which assert the intended integrated behavior.

| Check | Observed outcome |
| --- | --- |
| `git merge --no-commit --no-ff origin/feat/compose-ui` onto current main | Conflicts in `android/app/build.gradle.kts` and `MainActivity.kt` |
| `:app:compileFakeDebugKotlin`, after resolving only textual conflicts | **Fails:** non-exhaustive `LedgerError.userMessage` |
| `lintFakeDebug` and `lintRealDebug`, after compile adaptation | **Both fail:** 1 error and 14 warnings each; error is `SuspiciousIndentation` at `DataManagementScreen.kt:77` |
| Both unit-test compilations, before test adaptation | **Both fail:** removed `InMemoryLedger` references |
| `assembleFakeDebug` and `assembleRealDebug`, after compile adaptation | **Both complete successfully** within the continued build; the overall command still fails its lint/test-compilation tasks |
| `testFakeDebugUnitTest`, after both adaptations | 505 tests, 7 failures, 0 skipped: 498 existing tests pass; all 7 review probes fail |
| `testRealDebugUnitTest`, after both adaptations | 505 tests, 7 failures, 0 skipped: 498 existing tests pass; all 7 review probes fail |
| `git diff --check --cached` on the trial merge | Fails: new blank line at EOF in `SettingsScreen.kt:139` |
| Fresh API 37 emulator, realDebug startup | Launches to setup; no app startup exception observed in the collected AndroidRuntime log |
| Synthetic setup, force-stop, reopen | Identity persists and dashboard returns, contradicting the UI's in-memory-only notice |
| Real plaintext backup through Android document picker | Creates a **1,577-byte JSON backup containing the synthetic identity**, named `IOU-SIMULATION-EMPTY-backup.txt`; the app says no financial data was written |
| Debug merged manifests | Main's backup rules/cleartext policy retained; real retains WorkManager/network permissions and excludes `FOREGROUND_SERVICE`; biometric dependency also adds `USE_FINGERPRINT` |

The regression failures are intentional assertions of desired behavior, not newly introduced production faults. They expose gaps missed by the existing suite. The test named `real backup picker...` uses fake service fixtures to exercise the shared, flavor-independent picker code; the separate emulator test verifies the actual real backend.

**Limits of completed validation:** no signed-release build or upgrade, full UI journey suite, API 30 device run, real biometric/credential test, provider-failure test, full backup/restore/import round trip, or accessibility audit was completed. The device checks used a fresh headless AVD and synthetic data only. The 498-test result applies to the diagnostic adaptations, not the unmodified PR or an approved final merge.

## Confirmed pre-merge findings

Severity: **P1** blocks integration or risks materially misleading financial/security behavior; **P2** is a functional/lifecycle defect to fix before approving this integrated UI. Smaller cleanup and unverified platform risks are listed separately.

There are **14 findings: 5 P1 items (F1–F5) and 9 P2 items (F6–F14)**. Each has a correction and acceptance criterion below.

Unless otherwise stated, paths beginning `ui/`, `api/`, `store/`, or `data/` are relative to `android/app/src/main/kotlin/io/github/codex07/iou/`. Line references describe the reviewed source; the temporary mapper adaptation adds two lines near that file's end.

### F1 — Resolve both merge conflicts without discarding current main's backend/security integration (P1)

**Locations:** `android/app/build.gradle.kts:102`; `android/app/src/main/kotlin/io/github/codex07/iou/MainActivity.kt`.

Both branches changed the activity and dependency block. Choosing one whole Gradle side loses either main's `lifecycle-process` or the UI's runtime/viewmodel Compose dependencies. Replacing the placeholder activity with the UI is necessary, but accepting its lifecycle policy unchanged produces F6.

**Required:** retain all three lifecycle dependencies, biometric support, main's Room/serialization/Ktor/WorkManager/build configuration, and `IouApplication` → `AppSecurity.install()`. Reconcile the activity with the existing process-level security gate; retain `FragmentActivity` support for `BiometricPrompt`. Preserve main's real bindings and manifest/backup rules. Do not revert the real flavor to `FakeApis` just to make the UI's assumptions true.

**Acceptance:** no unresolved paths or conflict markers; both flavors compile; review the resolved activity and manifests, then run the final validation matrix below.

### F2 — New error variants make the combined production code uncompilable (P1)

**Locations:** `ui/forms/LedgerErrorMessage.kt:8`; `api/LedgerError.kt:75–80` on main.

The sealed `LedgerError` gained `FileAccessFailed` and `RestoreFailed`, but the UI's exhaustive `when` handles neither. The compiler explicitly reports both missing cases.

**Required:** add meaningful explicit messages and test them. For document failures, account for a document that may already have been created or partially written; never imply it is a usable completed backup. For `RestoreFailed`, preserve the contract's “nothing changed” meaning. Do not hide future missing cases with a blanket `else`.

**Acceptance:** both production compilations pass; representative file-access and restore-failure errors render actionable text, without leaking opaque internals.

### F3 — UI tests still instantiate the removed fake store (P1)

**Locations:** `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/OverviewViewModelTest.kt:6,65,111`, `AdvancedManagementTest.kt:293`, `SettingsSecurityTest.kt:30`.

Main moved the fake onto the shared `LedgerStore` and removed `fake.InMemoryLedger`. Both test compilations fail on these references.

**Required:** migrate fixtures to the current supported fake factory, e.g. `FakeApis.create(FakeApis.store())`, retaining the original empty-versus-seeded semantics. Also update tests that deliberately assert obsolete behavior: the immediate foreground fallback in `SettingsSecurityTest`, and the no-token membership/deleted-handle assertion in `AdvancedManagementTest`.

**Acceptance:** both test tasks compile and run without resurrecting the obsolete store or excluding UI tests. Tests assert the integrated contracts in F6/F8 rather than preserving prototype behavior.

### F4 — The PR's known lint error remains in both merged flavors (P1)

**Location:** `ui/screens/DataManagementScreen.kt:76–92`, especially line 77.

The history branch indents the `if`, `items`, and result block as if they continued `val history`. Android lint reports `SuspiciousIndentation`; the downloaded PR report and both local lint reports reproduce it.

**Required:** fix the block's indentation/bracing and review its actual control flow. Do not suppress the check or add a baseline merely to get a green build.

**Acceptance:** `lintFakeDebug` and `lintRealDebug` pass on the final merge. The current CI only invokes fake lint; include real lint in the merge validation, and preferably in CI.

### F5 — Real financial operations are falsely presented as empty simulations (P1)

**Locations:** `ui/settings/BackendCapabilities.kt:3–8`; `ui/viewmodel/DataManagementViewModel.kt:81–84,119–129,145–150,211–213`; `ui/screens/DataManagementScreen.kt:70,89,98–110,120,125–129,141–143,174`; `ui/IouRoot.kt:72`; `ui/screens/SettingsScreen.kt:75–85,112`; `android/app/src/main/res/values/strings.xml:39`.

Capabilities are hardcoded for the old state where both flavors were fake. Current main injects `RealBackend` in the real flavor. The UI therefore gives real backups/exports `text/plain` and an `IOU-SIMULATION-EMPTY-*.txt` name, advertises canned scenarios, and says real restore/import operations do not read or change on-disk data. Setup/settings also say persistence and security persistence are unfinished. The dashboard says backup controls are planned for a later phase even though its button opens them.

**Device reproduction:** install realDebug on a fresh AVD, create a synthetic identity, choose backup, disable encryption, acknowledge its warning, choose a document and save. The actual JSON file contains the identity and ledger structures (1,577 bytes in this fixture). The success message says: “Backup simulation completed. No financial data was written; the selected empty document is not a usable backup.” The same real identity survives force-stop/relaunch.

**Impact:** a user can discard a genuine backup or share a file falsely described as empty. Restore/import confirmations misrepresent real mutations. ZIP exports mislabeled as text also conflict with the import picker's `application/zip` filter. This is not cosmetic copy drift.

**Required:** make data-service capabilities flavor-aware; correct filenames, MIME types, warning/confirmation/result text, setup and settings notices, and dashboard strings. Gate canned simulation controls to the fake flavor. A single boolean change is insufficient because several messages are unconditional. Treat real and fake timeout capability separately from their persistence capability: current main wires the process gate for both flavors.

**Acceptance:** real plaintext/encrypted backup, normalized ZIP export, report CSV, restore, undo, import and undo produce truthful prompts/results and correctly typed files; fake remains clearly labeled. Verify actual bytes and data effects, not just the displayed success message. Retain explicit warning/acknowledgement for real plaintext backups.

### F6 — UI relocking overrides the backend's persisted timeout policy (P2)

**Locations:** PR `MainActivity.kt:61–67`; `ui/viewmodel/SecurityViewModel.kt:19,23,50–51`; main `security/AppSecurity.kt:29–34` and `store/DeviceSettingsStore.kt:47–85` (`TimedLockGate`).

The activity calls `background()` on pause, and the ViewModel sets `localLocked=true` on every background transition. Its `locked` property ORs this with the backend lock flag. A short return therefore locks even if main's 1/5/15-minute timeout has not expired. External activities such as document pickers participate in this problem. An existing test explicitly enforces the old conservative fallback.

**Required:** use `SettingsApi.observeLockRequired()` and main's process lifecycle as the policy authority. Preserve a privacy cover during foreground transitions, cold-start readiness and ledger replacement, without inventing another timeout. Keep credential/biometric success as the only path to `markUnlocked()`. Coordinate `FLAG_SECURE` ownership with main's application callbacks rather than leaving competing independent policies.

**Acceptance:** the short-return regression probe passes; test each configured timeout below and above its boundary, immediate mode, cold start, rotation, document picker return, credential activity, prompt cancellation and ledger replacement. Native frame/credential ordering still needs device validation; the probe verifies the policy conflict, not Android timing.

### F7 — A failed generation observer can cease to fail closed (P2)

**Location:** `ui/viewmodel/SecurityViewModel.kt:33–35,44–47`.

The generation collector's failure handler sets `loading=true` and covers content. A later, independent settings/lock emission unconditionally sets `loading=false`. The generation collector has terminated, but the UI can become uncovered and will no longer receive replacements through that collector.

**Required:** track generation readiness/error and settings readiness/error independently; uncover only when all required streams are healthy. Supply a deliberate recovery/retry path or require reopening. Do not let unrelated settings success erase a generation failure.

**Acceptance:** the regression probe fails the generation stream and then emits screen-protection settings; content must remain covered. Also test a later authentication/lock emission and cancellation of any pending enable/auth operation. This is a reproduced failure-state bug, not evidence of an observed biometric bypass in normal operation.

### F8 — History ignores main's new row tokens and disables supported reverts (P2)

**Locations:** `ui/forms/RevisionActions.kt:8–20`; `ui/screens/BrowseComponents.kt:144–149`; main `api/ReadModels.kt:276–280`.

Main now supplies `RevisionView.token` for the actual history subject, including memberships and deleted handles. The UI still reconstructs tokens from live handles/aliases and accepts only the group's own row for group history. It also unconditionally substitutes member-management guidance for membership revert actions. Both targeted probes have a valid current revision token but receive no UI action.

**Required:** use the selected revision's current token for its entity, expose supported membership/deleted-handle history operations, and keep the existing identity-change safeguards and backend validation. Never substitute a person/group token for a different row. Update the obsolete test that expects no action.

**Acceptance:** both probes pass; stale tokens fail safely; membership changes still respect balance/rule validation. Reverting a deleted handle changes its historical business fields while preserving deletion under main's rules—it must not be advertised as undeleting that handle.

### F9 — Leaving an import preview does not end its real backend session (P2)

**Locations:** `ui/viewmodel/DataManagementViewModel.kt:60,217–225`; main `api/ImportExportApi.kt:39–43`.

Clearing `rawPreview`, discarding the form, ending it or clearing the ViewModel never calls the newly added `discardImport(preview)`. A spy-backed probe observes zero disposal calls after the user discards a prepared preview.

**Required:** dispose the active session when a preview is abandoned, including route removal and generation invalidation. Handle cancellation while parsing so a late-created preview is also disposed. Avoid relying solely on `onCleared`, because current navigation retains ViewModels (F13). Successful commit/declined identity already have backend cleanup; make duplicate cleanup harmless.

**Acceptance:** discard, Back, replacement, cancellation and ViewModel clear release the session; stale previews cannot later commit. Main now bounds sessions to one and parsing another file clears the earlier session, so this is retention of an abandoned session, not an unbounded backend-session accumulation claim.

### F10 — Interrupted restore/undo recovery is never communicated to the user (P2)

**Locations:** main `api/BackupApi.kt:29–35`; UI catalog/root wiring in `ui/viewmodel/DataManagementViewModel.kt:19–23` and `ui/IouRoot.kt`.

Main added `observeRestoreInterrupted()` and `dismissRestoreInterrupted()`. No UI path consumes either. A process killed before the restore commit point can recover correctly, but the required one-time “nothing changed” notice never appears.

**Required:** observe this at an appropriate application/root level, show the interrupted-operation outcome after security/setup readiness, and dismiss it only after the notice is handled. Keep interruption before commit distinct from committed replacement.

**Acceptance:** kill/restart during pre-commit restore and undo, verify the original ledger and one-time notice, then verify acknowledgement persistence. Normal launches and successful committed replacement must not show a false interruption warning.

### F11 — Generation publication clears busy state before restore finishes (P2)

**Locations:** `ui/viewmodel/DataManagementViewModel.kt:41–44,63–76`; main `data/RestoreProcedure.kt` generation publication during replacement.

The watcher deliberately avoids cancelling an initiating replacement, but resets state to a fresh `DataState` with `busy=false` as soon as its generation changes. A gated restore probe publishes a generation and suspends before returning; the ViewModel already reports idle.

**Required:** distinguish invalidating old selections from completing the current operation. Preserve operation identity/busy status until the initiating restore/undo reaches a terminal result, and coordinate global navigation/security reset with delivery of its final outcome. Reject duplicate/conflicting actions during that interval.

**Acceptance:** the gated probe remains busy until return, and tests cover success/failure/cancellation, external replacement and undo. Verify the user-visible completion message survives the coordinated navigation change. The state defect is reproduced; an actual overlapping device operation or data corruption was not demonstrated, since root navigation may cover/remove the screen.

### F12 — UI offers XLSX although the integrated backend always rejects it (P2)

**Locations:** `ui/screens/DataManagementScreen.kt:165`; `ui/settings/DataModels.kt:11–13`; main `store/ExportRules.kt:18`.

XLSX is selectable and passes UI validation. Both backends reject it as `InvalidField("format")`; it is explicitly an M2 feature. The UI first invokes document creation, so a destination can be created before rejection, then shows an unhelpful generic form error.

**Required:** remove or disable XLSX with a clear availability explanation for the current milestone; validate support before launching the picker. Do not expand this integration PR into implementing M2 to satisfy an obsolete UI choice.

**Acceptance:** unsupported XLSX cannot create a document or reach export. Verify supported normalized ZIP and report CSV, Excel-friendly mode, scope and date restrictions.

### F13 — Popped routes retain their ViewModels and draft state (P2)

**Locations:** `ui/IOUApp.kt:47–55,148–165,203–220,243–274`; `ui/forms/SafeFormViewModel.kt:85–89`.

Forms get unique UUID route keys but `hiltViewModel(key=route)` uses the activity's ViewModelStore. Removing a Compose route does not clear that store. `finish()`/`end()` cancel work but do not dispose the ViewModel or necessarily erase its fields. Repeated form visits retain drafts/payment details and ViewModels until the activity is destroyed; keyed detail ViewModels are also retained. A saveable-state holder does not establish ViewModel lifetime.

This is also an [existing unresolved PR comment](https://github.com/Code-X07/IOU-App/pull/1#issuecomment-6656753459), independently confirmed from the navigation source.

**Required:** provide destination-scoped ViewModelStore ownership, using an appropriate navigation owner or explicit stores with disposal. Clear popped routes and obsolete saved state; retain the parent form while a nested create-group flow is active, and restore the intended scope across configuration change.

**Acceptance:** repeated open/cancel/save/pop clears the removed ViewModels and collectors; generation replacement clears obsolete destinations; nested group creation preserves the parent draft; rotation does not discard an active form. No heap-exhaustion crash was claimed or measured.

### F14 — Advanced-action exceptions display a success headline (P2)

**Locations:** `ui/viewmodel/AdvancedViewModel.kt:71–83`; `ui/components/AdvancedActions.kt:46`.

Advanced writes correctly catch thrown storage exceptions and report that an operation could not be confirmed, but every `message` is displayed under “Ledger updated.” A journal/storage failure during delete, revert, merge or membership actions therefore presents a success heading above an uncertain/failure message.

**Required:** distinguish successful, failed and uncertain outcomes in the state/presentation. An unconfirmed write must prompt inspection/reload before retry, not assert that the ledger changed.

**Acceptance:** inject a throwing write API and verify there is no success heading, busy/pending state is cleared, and the next action is based on refreshed data. Entry/metadata writes already catch ordinary storage exceptions through `SafeFormViewModel`; no uncaught-write defect was established for those paths.

## Remaining integration checks and smaller actions

These are explicit validation requirements or bounded cleanup, not additional claimed reproduced failures.

| Item | Required action / evidence |
| --- | --- |
| SAF permission lifetime | `DataManagementScreen.kt:47–50` persists every returned grant and treats persistence failure as total access failure; no grant-release path exists. Decide whether immediate operations need persisted access, use offered grant flags, and release grants when no longer needed. Test transient-only/denied/revoked access and actual stream errors. Android distinguishes current URI access from persistence across restart; see [official SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions). No failing provider was reproduced here. |
| Android security ordering | Test process/Activity foreground ordering, `FLAG_SECURE` ownership, Recents/screenshots, rotation during prompt, credential activity, cancelling then retrying, and backgrounding while enable-lock persistence is suspended. The mutable `authenticationId` callback association deserves a delayed-callback test; it is not listed as a proven race. |
| Real data-service integration tests | Existing UI tests mainly use `FakeApis`; even `testRealDebugUnitTest` does not turn those fixtures into real backend tests. Add ViewModel integration coverage with `RealBackend`/real APIs and a test `DocumentStore` for file bytes, parsed preview lifecycle, mutation, partial undo and replacement. Add Android tests for SAF/Compose/lifecycle boundaries. |
| Compose instrumentation | No Compose UI test dependency/runner or `androidTest` UI suite was found in this change. Pure navigation/value tests do not validate destination disposal, semantics, system pickers or lifecycle ordering. Establish focused instrumented coverage for these risky boundaries; a wholesale test-framework rewrite is unnecessary. |
| Manifest checklist | The biometric dependency adds **both `USE_BIOMETRIC` and legacy `USE_FINGERPRINT`**. The manual checklist currently only anticipates the former. Confirm the release manifest, document the transitive permission or remove it intentionally if appropriate; do not treat it as a discovered financial-data permission. Retain the existing backup/cleartext and debug-only receiver constraints. |
| Whitespace | Remove `SettingsScreen.kt`'s extra blank line at EOF; require `git diff --check` to pass. |
| Lint warnings | Triage 14 warnings per flavor. Resource pluralization/unused phase strings, missing launcher icon, preference KTX use and the hardcoded biometric version are lower priority. Dependency-version notices and `OldTargetApi` are not reasons to upgrade the platform during this merge; those warnings do not prove runtime failures. |
| Documentation | Update PR title/body for Phases 1–6, current real backend, remaining limitations and final validation. Update the manual app-lock checklist to use the new UI instead of the placeholder. Correct stale simulation/Phase-1 copy. README's milestone-completion claim must not be used as UI integration sign-off. |
| PR readiness | Keep draft until fixes and validation are complete, then mark ready and obtain review of the final combined SHA. No submitted review existed at inspection. Branch protection is off, so GitHub will not reliably enforce this report's gate; adding required checks is a recommended repository-policy improvement, not an existing requirement falsely attributed to GitHub. |

## Required validation matrix for the fixed merge

Run against the actual final merge result with current main; neither the original PR's test count nor this diagnostic clone substitutes for it.

| Area | Cases to cover before approval |
| --- | --- |
| Build and CI | Both debug flavors compile/build; both lint tasks; both unit suites including meaningful regression tests; domain-purity check; no conflict markers; clean diff check. |
| Fresh install and persistence | Real setup, person/group/entry creation, force-stop/reopen, retained profile/settings/ledger; fake seeded data and truthful reset behavior; loading/error/retry states. Synthetic setup persistence passed here, but full financial data persistence was not retested through UI. |
| Financial forms | Expense Equal/Percentage/Shares/Exact; note-only edits preserve stored FX/shares; changed amount/currency forces appropriate rate resolution; stale/manual/bank-actual rates; loans, transfers, partial repayment; personal multi-party `NeedsGroup` creates a group and returns to the draft; amount/date validation and duplicate-save guard. |
| People/groups/advanced | Handle defaults after merge/unmerge, canonical balances, membership balance constraints/re-add, archived/deleted list reachability, soft delete/restore, settlement draft direction and currency, stale plans, revisions using the selected row token, conflict-to-draft recovery. |
| Replacement/concurrency | Restore/undo from current UI, active drafts/previews invalidated, old tokens fail, no old route resurrected, busy/outcome lifetime preserved, interrupted-operation notice; storage errors and commit uncertainty; concurrent writes stale import previews. |
| Data files | Encrypted and acknowledged plaintext backup, real bytes and correct names/MIME, wrong/missing password, damaged/future-schema file, storage full/revoked grant/partial output; normalized ZIP export/import round trip, report scope/date, foreign-identity confirmation, invalid-row skipping, keep/take choices and refresh, complete/partial import undo. |
| Security | Cold start and each timeout, enable/disable persistence, unavailable screen lock, biometric and device credential, cancel/lockout/retry, rotation, screen-off/background, picker return, generation failure/replacement, screenshot/Recents protection and no financial-content flash. |
| Navigation and presentation | Popped ViewModels disposed; nested forms retained correctly; discard confirmation; Back and process recreation; compact/expanded layouts, keyboard/insets, long labels, dark/light/system theme, large font, TalkBack, balance masking, reduced-motion behavior. |
| Release boundary | If this merge is to ship immediately, complete the existing manual release checklist on a disposable upgrade image: signed APK/certificate, install-over-upgrade, release permissions/components, Auto Backup behavior and device KDF timing. These are release gates, not a claim that all must be repeated to repair an isolated UI typo. |

Suggested final command from `android/`:

```powershell
.\gradlew.bat --no-daemon lintFakeDebug lintRealDebug testFakeDebugUnitTest testRealDebugUnitTest assembleFakeDebug assembleRealDebug
```

Then inspect the final branch diff and rerun its clean-merge check against the then-current remote main. If main or the PR head moves, record the new SHAs and rerun affected checks.

## Recommended order of work

1. Integrate current main in an isolated branch and resolve F1; fix F2–F4 so the real merge can be tested without diagnostic adaptations.
2. Correct capability/file/confirmation behavior (F5) before exercising real user data; retain only synthetic fixtures during development.
3. Reconcile security F6–F7 and native lifecycle/secure-window ownership.
4. Adapt history and data lifecycles F8–F12; implement route disposal F13 and truthful uncertain outcomes F14.
5. Update obsolete tests, promote the review probes into appropriate permanent regressions, add real API/Android coverage, and complete the validation matrix.
6. Refresh documentation/PR metadata, fix the diff warning, inspect final CI and review the final combined commit. Only then mark the PR ready and merge.

M2 XLSX/group-row import and unrelated platform upgrades should stay out of this integration unless separately scoped. The current shared money/split/store engine was not shown to require replacement; the failures found are predominantly UI assumptions and lifecycle/API integration.

## Evidence retained locally

All experiments and generated files are under `temp/`; this report is the only deliverable added outside it. No branch was pushed, no PR comment was posted, and no remote merge occurred.

| Evidence | Workspace-relative location |
| --- | --- |
| Clone, resolved diagnostic merge and added probes | `temp/pr1-integration-review-20261106/` |
| PR/base/check/comment snapshots | `temp/pr1-metadata.json`, `temp/main-branch-metadata.json`, `temp/main-ci.json`, `temp/pr1-review-comments.json`, `temp/pr1-final-status.json` |
| Original failed PR CI and downloaded reports | `temp/pr1-ci-failure.log`, `temp/pr1-ci-reports/` |
| Production compilation blocker | `temp/merged-compile.log` |
| Both-flavor build/lint/test-compilation results | `temp/merged-checks.log` |
| Full test run including seven failures | `temp/merged-tests.log` and clone `android/app/build/test-results/` |
| Compile/fixture-only diagnostic patch | `temp/diagnostic-adaptations.patch` |
| Added test sources | Clone `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/IntegrationDataReviewTest.kt`, `IntegrationRevisionReviewTest.kt`, `IntegrationSecurityReviewTest.kt` |
| Startup/restart and SAF UI evidence | `temp/real-startup.xml`, `temp/real-restart.xml`, `temp/real-backup-screen.xml`, `temp/real-plain-backup-screen.xml`, `temp/real-backup-picker.xml`, `temp/real-backup-result.xml` |
| Actual synthetic JSON backup | `temp/synthetic-real-backup.txt` (synthetic data only) |
| Disposable device/build state | `temp/android-review-home/`, `temp/gradle-review-home/` |

The diagnostic clone is intentionally left as an uncommitted merge experiment. It is not a ready-to-push implementation branch. The disposable emulator and its isolated ADB server were shut down after testing; their data and the build caches remain under `temp/` for reproducibility.
