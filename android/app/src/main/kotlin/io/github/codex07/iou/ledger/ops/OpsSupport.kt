package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.ResultScope
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import java.time.Instant

internal fun ResultScope.checkBasis(ctx: WriteContext, basis: LedgerGeneration) {
    ensure(basis == ctx.generation) { LedgerError.LedgerReplaced }
}

/** Generation first (`LedgerReplaced`), then version (`StaleVersion`) (spec §5.1). */
internal fun ResultScope.checkToken(ctx: WriteContext, entity: LedgerEntity, token: EditToken) {
    ensure(token.generation == ctx.generation) { LedgerError.LedgerReplaced }
    ensure(token.version == entity.meta.version) {
        LedgerError.StaleVersion(entity.entityId, token.version, entity.meta.version)
    }
}

/**
 * The row a token edits. Generation first, before the lookup: a token from a replaced ledger is
 * `LedgerReplaced` even when the restored ledger lacks the row. Then `NotFound`, then `StaleVersion`.
 */
internal fun <E : LedgerEntity> ResultScope.edited(ctx: WriteContext, token: EditToken, id: String, find: (LedgerState) -> E?): E {
    ensure(token.generation == ctx.generation) { LedgerError.LedgerReplaced }
    val entity = find(ctx.state) ?: fail(LedgerError.NotFound(id))
    checkToken(ctx, entity, token)
    return entity
}

internal fun ResultScope.checkAmount(money: Money) {
    ensure(money.amountMinor > 0) { LedgerError.InvalidAmount }
    ensure(money.amountMinor <= MAX_MINOR) { LedgerError.AmountTooLarge }
}

internal fun WriteContext.newMeta(): SyncMeta = SyncMeta(createdAt = now, updatedAt = now, authorId = meId)

/** The entity's next version: `version + 1`, `updatedAt = now`, author me, and the given deleted state. */
@Suppress("UNCHECKED_CAST")
internal fun <E : LedgerEntity> WriteContext.touch(entity: E, deletedAt: Instant? = entity.meta.deletedAt): E =
    entity.withMeta(
        entity.meta.copy(updatedAt = now, authorId = meId, version = entity.meta.version + 1, deletedAt = deletedAt),
    ) as E

/** Runs the validator on the state [changes] would produce; the first violation becomes the error. */
internal fun ResultScope.validated(ctx: WriteContext, changes: List<Change>, snapshots: List<FxSnapshot> = emptyList()): WritePlan {
    if (changes.isEmpty() && snapshots.isEmpty()) return WritePlan.Empty
    val violations = LedgerValidator.validate(ctx.state, ctx.state.applying(changes, snapshots), WriteOp.Edit(changes))
    if (violations.isNotEmpty()) fail(violations.first().error)
    return WritePlan(changes, snapshots)
}

internal fun ResultScope.person(ctx: WriteContext, id: PersonId): Person =
    ctx.state.people[id] ?: fail(LedgerError.NotFound(id.value))

internal fun ResultScope.group(ctx: WriteContext, id: GroupId): Group =
    ctx.state.groups[id] ?: fail(LedgerError.NotFound(id.value))

internal fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }
