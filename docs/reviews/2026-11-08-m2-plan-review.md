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

# M2 plan review, round 5 (2026-11-08)

Two findings, both real.

## Findings

1. **[Medium] Skipping an invalid handle still changes its restore cascade** (Part 5, `ImportAnalysis.build` / `plan`). `sourceDeletedAt` was built from every decoded row, including the handles and memberships that validation later rejects. Round 4's duplicate pass took every owned row missing from the classified rows for a duplicate, so a skipped invalid handle still moved the local row into the cascade of a person kept deleted. Restoring the person then brought the handle back. Round 2's `joinsCascade` (owner deleted by the import) read the same map and had the same flaw.
2. **[Medium] Resolving one of two due schedules removes the other's notification** (Part 4, `RecurringNoticePlanner` / `AndroidRecurringNotices`). When the group shrank to one schedule, the planner removed the group summary. Android removes a summary's children with it, but the planner didn't redraw the unchanged schedule left, and its record still said "shown", so no later pass brought it back.

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | `classify` records `duplicateKeys`. The analysis it builds keeps the file's `deletedAt` for owners, and for owned rows only where they are kept classified rows or duplicates (`keptDeletedAt`). A rejected row therefore leaves no trace in `plan`, the preview or `joinsCascade`. Validation still runs with the full map; it only checks invariants, and moving the `deletedAt` of a row that is already deleted breaks none. Test: a new `GroupImportTest` case where the export's handle fails the IBAN check and is skipped; the handle keeps its earlier `deletedAt`, and restoring the person leaves it deleted. | Plan Part 5 Task 1 |
| 2 | The planner redraws, silently, the schedule left when the group shrinks to one (`ungrouped`). `AndroidRecurringNotices` already removes the summary before it redraws; a comment now says the order matters. Test: the planner's group-summary test checks that rent is redrawn silently once and not again. Device check 7 is extended. | Plan Part 4 Task 4, Global Constraints, manual check 9.7; spec §5.7 "Due" |

# M2 plan review, round 6 (2026-11-08)

Four findings, each verified against the plan code. All four were real and worth fixing, since each fix is small and local. Findings 1 and 2 can leave a notification wrong or missing; findings 3 and 4 make a later restore bring back, or leave out, the wrong rows.

## Findings

1. **[Medium] Due notifications keep stale details** (Part 4, `RecurringNoticePlanner`). The record key held the oldest date, error type, backlog state and app-lock state, but not what the notification shows: description, amount and count. An edit, an imported schedule, a restore, or simply one more occurrence becoming due could change the text and leave the key the same, so the old text stayed.
2. **[Medium] Revoking and re-granting the permission can suppress a due notification for good** (Part 4, `AndroidRecurringNotices`). Without the permission, the record kept the earlier keys. Android can remove the notifications while the record still says they are shown, so once the permission was back, no pass redrew them until the key changed. A blocked channel had the same effect.
3. **[Medium] An imported deleted owned row can come back after its import is undone** (Part 5, `ImportUndo.ownUndo`). A handle the import created under a person it left deleted is created and deleted with the person's `deletedAt`, which puts it in the person's cascade. Undo skipped created rows that were already deleted, so restoring the person later brought the undone handle back. Memberships under a deleted group had the same problem.
4. **[Medium] A rejected owner row can still change an owned row's grouping** (Part 5, `ImportAnalysis.build` / `plan`). Round 5 dropped the file's `deletedAt` for rejected owned rows only. A skipped invalid person's `deletedAt` still drove the duplicate pass, and could move a duplicate handle out of the local person's cascade. This contradicts §7.5 (skipping an invalid row writes nothing for it).

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | Each record value is now `<alert key>|<digest of title and text>`. A change of the alert key alerts; a change of only the digest (app lock, description, amount, count) redraws silently. `dueNotice` no longer takes `silent`; the planner sets it. Test: the first planner test now also checks that an amount edit and a third due occurrence each redraw silently. | Plan Part 4 Task 4, Global Constraints; spec §5.7 "Due" |
| 2 | While notifications are disabled, or after a `SecurityException`, nothing is recorded as shown. A blocked due channel records no due notifications; a blocked posted channel records no detailed posted summary. Neither channel draws while blocked. The first pass that can draw again shows everything that is due; this alerts once. | Plan Part 4 Task 4 (notifier and its KDoc), Global Constraints; spec §5.7 "Due" |
| 3 | `ownUndo`: a row the batch created that is still deleted, in the cascade (same `deletedAt`) of an owner the batch didn't create, moves to the undo time. Test: a new `ImportUndoTest` case where a handle is created deleted under a person kept deleted; after the undo, restoring the person leaves the handle deleted. | Plan Part 5 Task 4, Global Constraints; spec §5.4 "Owner deleted by the import" |
| 4 | `keptDeletedAt` now keeps the file's `deletedAt` only for kept classified rows and duplicates, owners included. Without its owner's time, an owned row the file shows deleted counts as deleted apart. Test: a new `GroupImportTest` case where the export's person has a blank name and is skipped; the duplicate handle stays in the local person's cascade. | Plan Part 5 Task 1, Global Constraints |

# M2 plan review, round 7 (2026-11-08)

Two findings about round 6's fixes. The first was real. The second was not, though it pointed at a misleading comment.

## Findings

1. **[Medium] Re-granting notifications still fails when no pass sees the off state** (Part 4, `AndroidRecurringNotices.afterRun`). Round 6 cleared the record only when a runner pass found notifications disabled or a channel blocked. If the user switches notifications (or the due channel) off and on again between two passes, Android may remove the notification while the record keeps its key, so the next pass draws nothing.
2. **[Medium, not reproduced] A rejected owner can still regroup a duplicate owned row** (Part 5, the duplicate pass in `ImportAnalysis.plan`). The finding assumed that the loop visits a rejected owner and then computes `apart` against its missing time. It doesn't: the loop runs over `sourceDeletedAt.keys`, and inside `plan` that map is `keptDeletedAt`, which round 6 filtered to kept and duplicate rows. A rejected owner is therefore never visited, its rows are left as they are, and the test `a skipped invalid person row leaves its handle in the local cascade` matches the code. The round-6 comment "counts as deleted apart" refers to `deletedApart` in the NEW and CONFLICT branches, not to this pass, which made the misreading easy.

## Resolution (2026-11-08)

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | New `NotificationBlockReceiver` (real flavor, in the manifest, exported because the system sends the broadcasts) for `APP_BLOCK_STATE_CHANGED` and, for the due channel, `NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` (API 28+, minSdk 30). On either change it clears the record's due part; on an unblock it enqueues a one-time `RecurringPostWorker` run. `RecurringNotifiedFile.NAME` and `AndroidRecurringNotices.DUE_CHANNEL` (now internal) are shared with the receiver. That the broadcast arrives after an Android 13+ permission re-grant is unverified on paper, so new device check 8 covers it, and the plan says what to do if it doesn't. | Plan Part 4 Task 4 (receiver, manifest, file constant), Global Constraints, manual check 9.8; spec §5.7 "Due" |
| 2 | No behavior change. A comment on the duplicate pass now says that only owners in the map are visited, so a rejected owner's rows stay as they are. | Plan Part 5 Task 1 |
