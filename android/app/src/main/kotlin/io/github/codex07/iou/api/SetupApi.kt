package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow

sealed interface SetupState {
    data object NeedsSetup : SetupState
    data class Ready(val me: PersonRef) : SetupState
}

/** First launch: create me (the permanent global identity) and pick the home currency (spec §3.1). */
interface SetupApi {
    fun observeSetupState(): Flow<SetupState>

    /** Errors: `InvalidField("displayName")`; `CannotModifyMe` if setup already happened. */
    suspend fun completeSetup(displayName: String, homeCurrency: CurrencyCode): LedgerResult<PersonId>
}
