package io.github.codex07.iou.ui.settings

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ui.forms.userMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs a restore or undo-restore outside any screen's lifetime. A replacement changes the ledger generation, which
 * clears the navigation stack and every screen's ViewModel; the call must still finish and its outcome must still reach
 * the user. The root shows [outcome] and calls [consumeOutcome].
 */
@Singleton
class LedgerReplacements(private val scope: CoroutineScope) {
    @Inject constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()
    private val mutableOutcome = MutableStateFlow<String?>(null)
    val outcome: StateFlow<String?> = mutableOutcome.asStateFlow()

    /**
     * Starts [block] unless a replacement is already running (returns false then, and runs nothing). [onResult] gets the
     * terminal result, or null when it could not be confirmed, so a still-alive screen can update its own state.
     */
    fun run(block: suspend () -> LedgerResult<Unit>, success: String, onResult: (LedgerResult<Unit>?) -> Unit): Boolean {
        if (!mutableRunning.compareAndSet(false, true)) return false
        mutableOutcome.value = null
        scope.launch {
            var result: LedgerResult<Unit>? = null
            try {
                result = block()
            } catch (cancel: CancellationException) {
                // Scope cancellation ends the app; any other cancellation is an unconfirmed outcome, not a stranded caller.
                if (!scope.isActive) { mutableRunning.value = false; throw cancel }
                result = null
            } catch (_: Exception) {
                result = null
            }
            val text = when (result) {
                is LedgerResult.Ok -> success
                is LedgerResult.Err -> result.error.userMessage()
                null -> "The restore could not be confirmed. Review your records before retrying."
            }
            mutableOutcome.value = text
            mutableRunning.value = false
            onResult(result)
        }
        return true
    }

    fun consumeOutcome() { mutableOutcome.update { null } }
}
