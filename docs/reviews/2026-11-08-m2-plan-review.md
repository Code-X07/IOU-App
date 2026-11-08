# M2 plan review, round 1 (2026-11-08)

An independent review of the M2 implementation plans (`docs/superpowers/plans/2026-11-07-m2-*.md`). Every finding was checked against the plan text and against the code as the earlier parts leave it; all six were real.

## Findings

1. **[High] Enabling app lock leaves existing notifications unredacted** (Part 4, notice planner and `AndroidRecurringNotices`). The due-notification key ignored the app-lock state, so a detailed notification stayed detailed after app lock was switched on, on every later pass. A detailed "posted" summary also stayed when a later pass posted nothing, and no settings change triggered a pass. This breaks §5.7: while app lock is on, notifications reveal no people, amounts or descriptions.
2. **[Medium] Due-list observers can keep yesterday's state after the foreground catch-up** (Part 4, `StoreRecurringApi`). The date-relative reads took `store.today()` only when the ledger flow emitted. A pass with only confirm-first schedules writes nothing, so an open due list from 30 November stayed empty after the 1 December pass, while the runner already notified December's rent.
3. **[Medium] Archiving a referenced person or group leaves its due notification** (Part 4, runner triggers). The runner watched only generations and import batches, and only `RecurringApi` writes requested a pass. An archive through `PeopleApi` / `GroupsApi` held the schedule, but its notification stayed until the next trigger (possibly the six-hour worker). This breaks §5.7 and AC 31: held schedules have nothing due or notified.
4. **[Medium] Import undo merges deletions that were apart** (Part 5, `ImportUndo`, inherited from M1). Undo deleted independently restored owned rows and their restored owner with the same `now`. A handle deleted at t1 and its person at t2, both restored by an import, shared `deletedAt` t3 after the undo, so restoring the person brought the handle back too. This breaks the §5.2 restore cascade and §5.4.
5. **[Medium] The report recomputes EQUAL payer amounts by person ID** (Part 1, `LedgerExport.paidBy`, which Part 5's CSV and XLSX reports share). After an import from another installation, the stored allocation (the extra cent with X) and the recomputed one (the extra cent with the lower local ID) differ, so `paid_by` disagreed with the ledger. This breaks §4.4 (stored payer rows are authoritative), §7.2 and §7.4.
6. **[Medium] An unchanged due group alerts on every pass** (Part 4, `AndroidRecurringNotices`). Unchanged due notifications were suppressed, but with several schedules due the group summary was posted again on every pass on the default-importance channel. This breaks the §5.7 deduplication and AC 34.

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | The due key now ends with `open` / `locked`, so a lock switch redraws every due notification; when only the redaction changed, the redraw is silent (`DueNotice.silent`, `setSilent`). The notified file became `NotifiedRecord(due, detailedPosted)`: switching app lock on removes a detailed posted summary (`cancelPosted`). `RecurringNotices.redraw` (default empty) asks the runner for a pass. `AndroidRecurringNotices` takes the device settings `StateFlow` and emits on app-lock changes. Tests: the planner's lock-switch test and the runner's redraw test. | Plan Part 4 Tasks 2 and 4, Global Constraints, manual check 9.4; index type inventory; spec §5.7 "Notifications"; AC 34 |
| 2 | New `RecurringRunner.today` (a `StateFlow`, set by every pass). `StoreRecurringApi`'s date-relative reads restart on it (`flatMapLatest`) and still take `store.today()` on every emission. Test: new `StoreRecurringApiTest` (an open due list shows 1 December's occurrence after a pass that writes nothing). | Plan Part 4 Tasks 2 and 3; spec §5.7 "in the foreground" |
| 3 | `RecurringRunner.start` also requests a pass on every change of the due list, whatever wrote it (an archive or unarchive, a merge, a revert, a post or skip). A posting pass is therefore followed by one more pass, which finds nothing to post. Test: the runner test where an archive through `StoreGroupsApi` empties the reported due list. | Plan Part 4 Task 2, Global Constraints; spec §5.7 "in the foreground", "Posting or skipping … or putting it on hold" |
| 4 | Undo puts back each row's own pre-import `deletedAt`: `ownUndo` for a row the import restored, and the owner deletion for an owner the import restored (its cascade shares it). Only rows the import created are deleted with the undo time. Test: the `ImportUndoTest` case where a handle and its person were deleted apart; `ImportBench` gains `people`. | Plan Part 5 Tasks 2 and 4; spec §5.4 "Owner restored by the import"; AC 14 |
| 5 | `paidBy` reads the stored rows. EXACT uses the inputs. Unconverted EQUAL uses `paidMinor`. Converted EQUAL divides the paid total equally, with the extra minor units going to the payers with the larger stored amounts (stored order on ties). It never re-divides by person ID. Tests: two extra expenses in the Part 1 report test, unconverted and converted, with the extra cent on the higher ID. | Plan Part 1 Task 4 Step 6, Global Constraints; spec §7.2 "Report shape" |
| 6 | The planner returns `summary`: the group summary is drawn only when the group changes (a schedule shown, changed or cancelled), removed when one or no schedule is left, and otherwise left alone. The summary is built silent with `GROUP_ALERT_CHILDREN`, so only a new or changed due notification alerts. Test: the planner's group-summary test. | Plan Part 4 Task 4, manual check 9.7; spec §5.7 "Due"; AC 34 |

# M2 plan review, round 2 (2026-11-08)

Three more findings, each checked against the plan text, the spec and the current `LedgerStore`; all three were real.

## Findings

1. **[Medium] Auto-post can commit after the schedule is put on hold** (Part 4, `RecurringRunner.runSchedule`). Eligibility was checked before the rate request, and `RecurringOps.post` was then called without checking again inside the write lock. Starting a hold doesn't change the schedule's version, so the prepared token stayed valid, and expense validation accepts archived references. An archive made while the rate request was out let the expense post anyway, which breaks §5.7 and AC 31.
2. **[Medium] App-lock redaction waits for rate requests and posting** (Part 4, runner and `RecurringNotices.redraw`). The redraw signal asked for a whole pass, so a detailed notification stayed visible until every schedule's network work had finished, and longer still behind a pass already running. This breaks §5.7's notification privacy.
3. **[Medium] Import loses the source's cascade grouping for owned rows that are already deleted here** (Part 5, `ImportAnalysis.plan` and `ImportUndo`). The owner's delete cascade skipped owned rows that were already deleted. A row whose only difference from the file was its `deletedAt` was also classified as a duplicate. A handle deleted here on its own kept its earlier `deletedAt` when the import deleted its person, although the export shows the two deleted together. Restoring the person then left the handle deleted, which breaks §7.4.

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | The runner's posting write now checks `Recurrence.autoPosts` against the state inside the write lock. When the schedule is gone or held, it writes an empty plan and the schedule stops without a failure. Test: `a schedule put on hold while its rate is fetched is not posted`, where the quote source archives the group mid-request. | Plan Part 4 Task 2, Global Constraints; spec §5.7 "On hold"; AC 31 |
| 2 | New `RecurringRunner.redraw()` reports the current due list and the last failures at once. It posts nothing and doesn't wait for the pass lock. Every `RecurringNotices.redraw` emission runs it instead of requesting a pass. A separate `report` mutex keeps it and a pass's report from overlapping. Tests: `a redraw signal reports the due list at once, while a pass still waits for a rate` (the quote source waits on a deferred) and `every pass moves today`, which is now split out of the old redraw test. | Plan Part 4 Tasks 2 and 4 (port and notifier KDoc), Global Constraints; index row and type inventory; spec §5.7 "in the foreground", "Switching app lock" |
| 3 | `sourceDeletedAt` now also holds the file's `deletedAt` for handles and memberships. When the import deletes an owner, `joinsCascade` picks every owned row already deleted here that the file shows deleted with the owner, duplicates included. `Writer.regroup` moves those rows to the owner's new `deletedAt`, and the preview lists them as "deleted with <owner>". On undo, restoring that owner gives such rows back their own pre-import `deletedAt` instead of restoring them, and `ownUndo` now always uses the pre-import `deletedAt`. Tests: one new `GroupImportTest` case (preview, commit and restore) and one new `ImportUndoTest` case. | Plan Part 5 Tasks 1 and 4, Global Constraints; spec §7.4 "Deleted owners keep their cascade", §5.4 "Owner deleted by the import"; AC 14 |

# M2 plan review, round 3 (2026-11-08)

Two findings, both real.

## Findings

1. **[Medium] Existing handles don't join the restore cascade of an owner kept deleted** (Part 5, `ImportAnalysis.plan` / `writeOwned`). For an existing owned row, the CONFLICT branch handled only an owner the import deletes, plus the row's own choice. Example: the export has a person with an active handle, and locally the handle was deleted first and the person later. The import keeps the person deleted. The preview showed the handle as "deleted with <person>", but the handle kept its own earlier `deletedAt`, so restoring the person left it deleted. This contradicts §7.4 ("the owner ends deleted": an owned row the file shows active gets the owner's local `deletedAt`).
2. **[Medium] Undoing a restore onto a fresh install leaves the due notifications of the discarded ledger** (Part 4, `RecurringRunner.run` / `redraw`). Both returned early for a ledger without me, before reporting to the notifier. The notifications of the restored ledger therefore stayed through every later pass, worker run and restart. This breaks §5.7 ("a restore re-syncs all of them").

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | `writeOwned`'s CONFLICT branch gains the case of an owner kept deleted. The owned row (deleted here, since its owner is) gets the owner's local `deletedAt` through `Writer.regroup`, unless the file shows it deleted apart. In that case it stays apart, or moves 1 ns before the owner if it was deleted with the owner here. Import undo already gives such a row its pre-import `deletedAt` back (`ownUndo`, round 2). Test: a new `GroupImportTest` case where the person is kept deleted and the export shows the handle active; the preview, the shared `deletedAt` and the restore are all checked. | Plan Part 5 Task 1, Global Constraints |
| 2 | Without me, `run()` clears the failures and reports an empty due list with no posts, and `redraw()` reports an empty due list. The notifier then cancels every notification it recorded. Test: a ledger without me reports an empty list from both. | Plan Part 4 Task 2, Global Constraints; spec §5.7 "a restore re-syncs all of them" |

# M2 plan review, round 4 (2026-11-08)

One finding, real.

## Findings

1. **[Medium] Duplicates skip the cascade grouping under an owner kept deleted** (Part 5, `ImportAnalysis.plan`). `classify` drops rows whose business fields and deleted state match, because `deletedAt` is never compared. The round-3 handling for an owner kept deleted therefore reached only conflicts. Example: locally a person and their handle were deleted together; the export has both deleted, with the handle deleted first; the import leaves the person deleted. The handle kept the person's `deletedAt`, so restoring the person brought it back. This contradicts §7.4.

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | The kept-owner rule moves into a local `groupUnderKeptOwner`, which the CONFLICT branch now calls. After the owned rows are written, the plan also walks every owner in the file that the import leaves deleted here (`OwnerAction.NONE`, deleted locally). For each local owned row that the file contains but that isn't a classified row, it applies the same rule from the file's `deletedAt` values. Duplicates are still not shown in the preview, since their visible state doesn't change. Undo already gives such a row its own pre-import `deletedAt` back (`ownUndo`). Test: a new `GroupImportTest` case where both rows are duplicates and the export shows the handle deleted apart; the handle ends 1 ns before the person, and restoring the person leaves it deleted. | Plan Part 5 Task 1, Global Constraints; spec §7.4 "The owner ends deleted" |
