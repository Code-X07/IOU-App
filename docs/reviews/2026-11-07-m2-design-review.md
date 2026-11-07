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
