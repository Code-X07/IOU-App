package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.LedgerState

/** The [E] rules of spec §3.4. */
internal object EditRules {
    fun check(before: LedgerState, after: LedgerState, changes: List<Change>): List<RuleViolation> = emptyList()
}
