# M2 design review: multiple payers and recurring expenses

**Review date:** 2026-11-07

**Scope:** the spec changes for M2 (`docs/superpowers/specs/2026-11-02-iou-mvp-design.md` at `9a9d1a7`: §3.2, §3.4, §5.3, §5.7, §7.4, §12) and ADR-0013. An independent review; findings relayed by the owner.

## Findings

1. **[Medium] Pattern edits contradict `handledThrough` validation and the next-occurrence calculation** (§3.4 rule 0, §5.3, §5.7). Rule 0 required `handledThrough` to be an occurrence of the current pattern, but pattern edits and reverts keep it. Moving monthly rent from the 1st to the 5th with 1 December posted fails validation, and "first occurrence after `handledThrough`" gives 5 December while the spec promised 5 January.
2. **[Medium] Pending historical occurrences prevent the promised newer auto-posts** (rule 19, §5.7, AC 31–32). Posting is strictly in order with one cursor, yet dates before `autoPostFrom` were to wait while newer dates auto-posted. Today's occurrence can't be posted while older ones wait; moving the cursor would drop them unconfirmed. The same applies after unarchiving or switching auto-post on.
3. **[Medium] Taking an older imported schedule can block it on an already-posted occurrence** (§7.4, rules 18–19, §5.7). With November posted locally, taking an incoming schedule handled through October rewinds the cursor; the runner then tries November again, fails rule 18 and blocks every later date.
4. **[Medium] Some transitions bypass the backlog protection** (§5.3, §5.7). A revert to an earlier `autoPost = true` version keeps the old `autoPostFrom`; editing a held schedule away from its archived person or group ends the hold without a reset. Either way, held-up dates could be auto-posted.

## Resolution (2026-11-07)

All four were verified against the spec text and fixed in the spec and ADR-0013.

| Finding | Fix | Where |
| --- | --- | --- |
| 1 | Rule 0 no longer constrains `handledThrough` to the pattern. The next occurrence is the first of the **current** pattern after the handled date. A pattern change skips nothing by itself: the example now gives 5 December and says how to continue on 5 January (move `startOn`, or skip). The read model shows the next occurrence so the edit screen can show it before saving. | §3.2, §3.4 rule 0, §5.7 "Next and due", "Editing a schedule"; AC 33 |
| 2 | Strict order stays. Auto-post runs only while the **next** occurrence is on or after `autoPostFrom`; a backlog of older dates blocks auto-post for that schedule (newer dates included) until the user posts or skips through it, and the due notification says so. | §3.2, §5.7 "A backlog stops auto-post", notifications; AC 31–32 |
| 3 | The handled date is the later of `handledThrough` and the `recurringOn` of every expense posted from the schedule, deleted ones included. Taking an older `handledThrough` therefore can't make a posted date due again. | §5.7 "Handled through", "Deleting a posted expense", §7.4; AC 35 |
| 4 | One op-level rule: every write after which a schedule auto-posts (not deleted, `autoPost` on, not on hold) when it didn't before sets `autoPostFrom` to today. It covers create, update (switching auto-post on, or editing away the archived group or person), revert, restore, unarchive, import and import undo. `autoPostFrom` became local state: exported, never compared or taken on import. | §3.2, §5.3, §5.7 "On hold", "Auto-post starts", §7.4; AC 31–32 |

## Second round (2026-11-07): issues caused by the fixes

5. **[Medium] Import undo leaves undone occurrences counted as handled** (§5.4, §5.7 "Handled through"). Counting deleted posted expenses kept an occurrence handled after import undo soft-deleted the expense the import had brought: a confirm-first schedule at October, imported with a November expense and progress, then undone at once, skipped November silently.
6. **[Medium] The import cut-off rule conflicted with the general auto-post transition rule** (§5.7, §7.4). §5.7 reset `autoPostFrom` only on a change into auto-posting, §7.4 on any import of an auto-posting schedule. "Take incoming" of an older `handledThrough` on an already auto-posting schedule gave two outcomes: November re-posted at once, or waiting for confirmation.

### Resolution

| Finding | Fix | Where |
| --- | --- | --- |
| 5 | The handled date counts only **non-deleted** posted expenses. Deleting a posted expense through the `api` raises `handledThrough` to its `recurringOn` in the same write, so a user's delete still never makes the date due again. Import undo deletes its expenses outside that path and reverts the schedule's `handledThrough`, so the pre-import due list comes back exactly. | §5.4, §5.7 "Handled through", "Deleting a posted expense"; AC 35 |
| 6 | Two changes remove the ambiguity. (a) Import only moves `handledThrough` forward (the later of local and incoming, independent of the row's choice), so an import can't rewind a schedule. (b) One cut-off rule for every path: every write to a schedule except posting and skipping sets `autoPostFrom` to today when the schedule auto-posts afterwards, whether or not it did before. | §3.2, §5.3, §5.7 "The cut-off rule", §7.4; AC 35 |

## Third round (2026-11-07): undo validation and holds ended by merges

7. **[Medium] Step-by-step undo can't undo some unchanged group imports** (§5.4, §3.4 rules 3–4 and 10; AC 14, 16). Undo checked each step on its own, retried blocked steps and skipped them after a round without progress. Steps that are only valid together never pass: a new group with me and removed member A, an expense I paid €100 for A and A's €100 repayment. Deleting either entry alone breaks rule 4, and neither A's membership nor the group can go while the entries are live, so an unchanged import was left in place.
8. **[Medium] A merge can end a hold without resetting the cut-off** (§3.1, §5.6, §5.7; ADR-0003). Holds are judged on canonical people, so merging an archived contact into an active duplicate (or unmerging an active alias from an archived person) ends a schedule's hold. A merge writes only the source person and never a schedule, so `autoPostFrom` stayed before the dates that piled up during the hold, and the next runner pass auto-posted them.

### Resolution

| Finding | Fix | Where |
| --- | --- | --- |
| 7 | Undo first applies every step that change protection leaves and validates the resulting state once; if it holds, that is the undo. With nothing written since the import, that state is the pre-import state (plus kept FX snapshots and the created rows as deleted rows), so an unchanged import always undoes completely. Only when the whole set fails does undo go step by step; a round without progress then tries tied steps together (entry steps in one group sharing a removed member, joined transitively, since rule 4 judges them only together) before skipping anything. | §5.4 "The whole undo is validated first"; AC 16 |
| 8 | Holds are defined on canonical people, and every hold end is written: the op layer compares holds before and after each write, on every path, and writes a resume (`UPDATED`, "Resumed: <cause>") for each schedule the write takes off hold, unless the write already changes it. The cut-off rule therefore covers unarchives, reverts, imports, import undo, merges and unmerges alike. A resume changes no template field (no edit rule applies) and is written untagged by an import (undo doesn't revert or report it). Merge and unmerge may now write these resumes; they change no person, membership or entry, so unmerge still restores balances, memberships and canonical IDs exactly. A write that starts a hold needs no schedule write. | §5.2, §5.6, §5.7 "On hold", "The cut-off rule", "Merges"; ADR-0003 amendment, ADR-0013; AC 31; `docs/m2-ui-contract-changes.md` |
