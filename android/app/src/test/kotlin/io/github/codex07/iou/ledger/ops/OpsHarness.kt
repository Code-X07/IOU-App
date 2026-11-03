package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import java.time.Instant

/** Applies plans the way the fake and LedgerWriter do: rows replaced, one revision per change. */
class OpsHarness(initial: LedgerState) {
    val generation = LedgerGeneration("gen-1")
    var state: LedgerState = initial
        private set
    val revisions = mutableListOf<EntryRevision>()
    var lastPlan: WritePlan? = null
        private set
    var now: Instant = T0.plusSeconds(60)
    private val ids = SequentialIds("op")

    init {
        val entities: List<LedgerEntity> = initial.people.values + initial.handles.values + initial.groups.values +
            initial.members.values + initial.entries
        entities.forEach { record(it, RevisionAction.CREATED, null) }
    }

    fun ctx(): WriteContext = WriteContext(state, generation, now, ids)

    fun <T> run(op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> {
        val result = op(ctx())
        if (result is LedgerResult.Ok) {
            val plan = result.value.plan
            lastPlan = plan
            state = state.applying(plan.changes, plan.snapshots)
            plan.changes.forEach { record(it.entity, it.action, it.reason) }
        }
        return result.map { it.value }
    }

    fun token(entity: LedgerEntity): EditToken = EditToken(generation, entity.meta.version)

    private fun record(entity: LedgerEntity, action: RevisionAction, reason: String?) {
        revisions += EntryRevision(
            id = RevisionId("rev-${revisions.size + 1}"), entityType = entity.entityType, entityId = entity.entityId,
            version = entity.meta.version, action = action, authorId = ME, deviceId = DeviceId("device-test"),
            at = now, reason = reason, snapshot = entity,
        )
    }
}
