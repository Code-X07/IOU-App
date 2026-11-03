package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState

/**
 * The single validator every write path runs (spec §3.4): field validity, state invariants over the
 * complete state [after], and, for api writes, the edit rules over the changed entities.
 */
object LedgerValidator {

    fun validate(before: LedgerState, after: LedgerState, op: WriteOp): List<RuleViolation> {
        val structure = StateInvariants.structure(after)
        if (structure.isNotEmpty()) return structure

        val resolver = IdentityResolver(after)
        val balances: Balances? = try {
            LedgerEngine.compute(after)
        } catch (overflow: ArithmeticException) {
            null
        }

        // Order: field checks, then edit rules (what the user tried to do), then state invariants
        // (its consequences), each by rule number. Callers report the first one.
        val violations = buildList {
            if (balances == null) add(RuleViolation(0, LedgerError.AmountTooLarge))
            addAll(FieldRules.check(fieldTargets(after, op)))
            if (op is WriteOp.Edit) addAll(EditRules.check(before, after, op.changes).sortedBy { it.rule })
            addAll(StateInvariants.check(after, resolver, balances).sortedBy { it.rule })
        }
        return violations.distinct()
    }

    private fun fieldTargets(after: LedgerState, op: WriteOp): List<LedgerEntity> = when (op) {
        is WriteOp.Edit -> op.changes.map { it.entity }.filter { !it.isDeleted }
        WriteOp.Import, WriteOp.UndoImport ->
            (after.people.values + after.handles.values + after.groups.values + after.members.values + after.entries)
                .filter { !it.isDeleted }
    }
}
