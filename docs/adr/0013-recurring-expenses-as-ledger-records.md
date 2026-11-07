# ADR-0013: Recurring expenses are ledger records, posted in the foreground and by a background worker

**Date**: 2026-11-07
**Status**: accepted
**Deciders**: project owner, Claude (M2 design)

## Context

The owner wants repeating expenses (rent, bills, subscriptions) with a per-schedule choice between posting automatically and confirming each occurrence, auto-posting that happens while the app is closed, and notifications. The app is offline-first and single-device; until M2 it had no runtime permissions (spec §9); Android may delay background work; a foreign-currency expense in a CONVERT group needs a rate for its date. Schedules must survive restore and a new phone, and every change to ledger data must have history.

## Decision

A schedule is a `RecurringExpense` ledger record with sync columns, revisions, backup, export and import, owning its template payers and participants (`RecurringParticipant`). It stores `handledThrough`, the last occurrence posted or skipped, and derives the next one from its pattern. Posting an occurrence creates an ordinary expense (with `recurringId` and `recurringOn`) and moves `handledThrough` in the same journal entry. `RecurringRunner` posts auto-post schedules after start-up, on foreground, after a restore or import, and from a 6-hourly WorkManager worker in the real flavor. A notifier then shows one "due" notification per schedule that waits for the user and a "posted" summary, redacted while app lock is on, behind the `POST_NOTIFICATIONS` runtime permission.

## Alternatives Considered

### Alternative 1: Schedules as device-local settings outside the ledger
- **Pros**: no new table, rules, import or export work.
- **Cons**: lost on restore or a new phone; no history; not covered by undo.
- **Why not**: conflicts with the audit-history requirement and with backup completeness.

### Alternative 2: Store `nextDueOn` instead of `handledThrough`
- **Pros**: the next date is read, not computed.
- **Cons**: editing the pattern or the end date needs rules to recompute it, and an ended schedule needs a separate marker.
- **Why not**: the handled date is the fact; the next date follows from it and the current pattern.

### Alternative 3: Foreground posting only, no notifications
- **Pros**: no worker, no runtime permission, the same behaviour in both flavors.
- **Cons**: auto-post schedules post nothing until the app is opened, and confirm-first schedules wait silently.
- **Why not**: the owner chose auto-posting specifically to have entries appear on their own and to be told about them.

## Consequences

### Positive
- Posted occurrences are plain expenses: every existing rule, view, export and undo applies.
- Posting twice is impossible: the schedule's version and rule 18 guard it.
- Both flavors run the same runner; the worker only adds another trigger, and it waits on the store lock like any writer, so it can't interleave with a restore.

### Negative
- Three new tables in schema 2 and a new `RecurringApi` for the UI contributor.
- The app's first runtime permission, plus a small device-local file to avoid repeating notifications.
- The fake has no worker and no notifications, so the UI contributor checks those on the real flavor.

### Risks
- Android may run the worker hours late; opening the app always catches up.
- A schedule backfilled from a distant start date posts many entries at once; the UI should show what a new schedule will post.
