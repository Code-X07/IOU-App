# ADR-0007: Backup restore commits through an atomic database pointer

**Date**: 2026-11-02
**Status**: accepted
**Deciders**: project owner, Claude (spec review)

## Context

A JSON backup restore replaces the whole ledger and must survive process death at any moment: afterwards the app must have either the complete old database or the complete new one. The first design swapped files with two renames (`iou.db` → `pre-restore/`, `restore-tmp/` → `iou.db`) guarded by a journal, and recovery inferred the progress from which files existed. An external review found a state that recovery misread (undo, right after the journal was written) and a conflict between "finalize" in the recovery table and "roll back" in the error handling and acceptance criteria.

## Decision

Database files get generation names (`iou-<uuid>.db`). A pointer file `db-state.json` names the `current` and `previous` databases and is only ever replaced by one atomic rename, which is the single commit point of a restore or an undo. On start, recovery deletes every database file the pointer does not name. A marker file records the target of an in-flight operation only to choose the message shown after a crash.

## Alternatives Considered

### Alternative 1: Keep the rename swap and add journal phases
- **Pros**: fixed file name `iou.db`, smaller change to the spec.
- **Cons**: undo still needs several renames; every phase/file combination needs a recovery rule, and the review showed how easy it is to get one wrong.
- **Why not**: more states to reason about and test than the problem needs.

### Alternative 2: Restore by deleting and re-inserting all rows inside one Room transaction
- **Pros**: no file juggling at all.
- **Cons**: needs the old database's schema to accept the backup, migrations of older backups become awkward, and there is no cheap pre-restore copy for "undo last restore".
- **Why not**: loses the undo copy and complicates migrating old backups.

## Consequences

### Positive
- One commit point; recovery is "delete what the pointer doesn't name", with nothing to infer.
- Undo is a pointer swap and can itself be undone.

### Negative
- Room opens a database whose name comes from the pointer, so database creation in DI reads the pointer first.
- Android Auto Backup includes the pre-restore copy as well (paths can't be excluded by pattern).

### Risks
- Forgetting to checkpoint and close the live database before the commit would leave its `-wal` behind. Covered by the process-death tests at every step (spec §11).
