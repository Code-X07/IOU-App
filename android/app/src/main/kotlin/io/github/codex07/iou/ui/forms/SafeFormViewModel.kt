package io.github.codex07.iou.ui.forms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.LedgerGeneration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FormStatus(
    val loading: Boolean = true, val busy: Boolean = false, val dirty: Boolean = false,
    val error: LedgerError? = null, val message: String? = null,
    val invalidated: Boolean = false, val completedId: String? = null,
    val discard: Boolean = false, val exit: Boolean = false,
)

/** One form instance owns one basis/token lifetime. UI navigation must go through requestLeave. */
abstract class SafeFormViewModel(private val backup: BackupApi) : ViewModel() {
    protected val mutableStatus = MutableStateFlow(FormStatus())
    val status: StateFlow<FormStatus> = mutableStatus.asStateFlow()
    private var capturedBasis: LedgerGeneration? = null
    protected val basis: LedgerGeneration get() = checkNotNull(capturedBasis)
    private var opened = false
    private var watch: Job? = null
    protected val editable get() = !status.value.loading && !status.value.busy && !status.value.invalidated && status.value.completedId == null && status.value.error !is LedgerError.StaleVersion && status.value.error !is LedgerError.NotFound
    protected open fun cancelPending() {}

    protected fun initialize(load: suspend () -> Unit) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            try {
                capturedBasis = backup.observeLedgerGeneration().first()
                watch = viewModelScope.launch {
                    backup.observeLedgerGeneration().catch {
                        invalidate()
                    }.collect { if (it != basis) invalidate() }
                }
                load()
                mutableStatus.update { it.copy(loading = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableStatus.update { it.copy(loading = false, message = "Unable to open this form. Close it and try again.", invalidated = true) } }
        }
    }
    protected fun requireGeneration(token: EditToken) {
        if (token.generation != basis) invalidate()
    }
    protected fun changed(dirty: Boolean) { mutableStatus.update { it.copy(dirty = dirty, error = null, message = null) } }
    protected fun fail(error: LedgerError) {
        if (error == LedgerError.LedgerReplaced) invalidate()
        else mutableStatus.update { it.copy(error = error) }
    }
    private fun invalidate() {
        watch?.cancel()
        cancelPending()
        mutableStatus.update { it.copy(invalidated = true, error = LedgerError.LedgerReplaced, busy = false, discard = false) }
    }
    protected fun submit(write: suspend () -> LedgerResult<String>) {
        if (!editable) return
        // Set synchronously, before launching: two taps in the same frame cannot enqueue two writes.
        mutableStatus.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                when (val result = write()) {
                    is LedgerResult.Ok -> if (!status.value.invalidated) mutableStatus.update {
                        it.copy(busy = false, dirty = false, completedId = result.value)
                    }
                    is LedgerResult.Err -> { fail(result.error); mutableStatus.update { it.copy(busy = false) } }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableStatus.update { it.copy(busy = false, invalidated = true,
                message = "The save could not be confirmed. Close this form and check your records before trying again.") } }
        }
    }
    fun requestLeave() {
        if (status.value.busy) return
        mutableStatus.update { it.copy(discard = it.dirty && !it.invalidated && it.completedId == null,
            exit = !it.dirty || it.invalidated || it.completedId != null) }
    }
    fun end() { cancelPending(); watch?.cancel() }
    fun keepEditing() { mutableStatus.update { it.copy(discard = false) } }
    fun discard() { cancelPending(); mutableStatus.update { it.copy(discard = false, exit = true) } }
    /** Explicitly confirmed reload, never an automatic overwrite of a dirty edit. */
    protected fun reopen(load: suspend () -> Unit) {
        if (status.value.busy || status.value.invalidated) return
        cancelPending(); watch?.cancel(); opened = false
        mutableStatus.value = FormStatus()
        initialize(load)
    }
}
