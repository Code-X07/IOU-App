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
