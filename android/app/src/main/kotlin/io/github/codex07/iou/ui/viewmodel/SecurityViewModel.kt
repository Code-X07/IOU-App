package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AuthPurpose { UNLOCK, ENABLE_LOCK }
data class AuthenticationRequest(val id: Long, val purpose: AuthPurpose, val launched: Boolean = false)
data class SecurityState(
    val loading: Boolean = true, val foreground: Boolean = false, val enabled: Boolean = false,
    val backendLocked: Boolean = true, val screenProtection: Boolean = true,
    val generationFailed: Boolean = false, val settingsFailed: Boolean = false,
    val request: AuthenticationRequest? = null, val busy: Boolean = false, val message: String? = null,
) {
    val locked get() = enabled && backendLocked
    val unavailable get() = generationFailed || settingsFailed
    val covered get() = loading || unavailable || !foreground || locked
}

/** Lock policy lives in the core: [locked] follows SettingsApi.observeLockRequired. Not being in the foreground only covers content. */
@HiltViewModel
class SecurityViewModel @Inject constructor(private val settings: SettingsApi, backup: BackupApi) : ViewModel() {
    private val mutableState = MutableStateFlow(SecurityState())
    val state = mutableState.asStateFlow()
    private var nextRequest = 0L
    private var authJob: Job? = null
    init {
        viewModelScope.launch {
            var previous: io.github.codex07.iou.ledger.model.LedgerGeneration? = null
            backup.observeLedgerGeneration().catch {
                authJob?.cancel(); mutableState.update { it.copy(generationFailed = true, request = null, busy = false, message = "Ledger security status is unavailable. Reopen the app.") }
            }.collect { generation ->
                if (previous != null && previous != generation) {
                    authJob?.cancel()
                    mutableState.update { it.copy(request = null, busy = false, message = "The ledger changed. Authenticate again if app lock is enabled.") }
                }
                previous = generation
            }
        }
        viewModelScope.launch {
            combine(settings.observeSettings(), settings.observeLockRequired()) { config, lock -> config to lock }
                .catch { authJob?.cancel(); mutableState.update { it.copy(settingsFailed = true, request = null, busy = false, message = "Security settings are unavailable. Reopen the app.") } }
                .collect { (config, lock) -> mutableState.update { it.copy(loading = false, enabled = config.appLockEnabled,
                    backendLocked = lock, screenProtection = config.screenProtection) } }
        }
    }
    fun foreground() { mutableState.update { it.copy(foreground = true) } }
    fun background() { mutableState.update { it.copy(foreground = false) } }
    fun begin(purpose: AuthPurpose, deviceSecure: Boolean, available: Boolean) {
        if (state.value.request != null || state.value.busy || state.value.loading || state.value.unavailable) return
        if (!deviceSecure || !available) {
            mutableState.update { it.copy(message = "Set up a secure Android screen lock and an available strong biometric or device credential before continuing.") }; return
        }
        mutableState.update { it.copy(request = AuthenticationRequest(++nextRequest, purpose), message = null) }
    }
    fun launched(id: Long) { mutableState.update { if (it.request?.id == id) it.copy(request = it.request.copy(launched = true)) else it } }
    fun failedAttempt() { mutableState.update { it.copy(message = "Authentication was not recognized. Try again in the system prompt.") } }
    fun result(id: Long, success: Boolean, message: String? = null) {
        val request = state.value.request?.takeIf { it.id == id } ?: return
        if (state.value.busy) return
        if (!success) {
            mutableState.update { it.copy(request = null, message = message ?: "Authentication cancelled. Protected content remains locked when app lock is enabled.") }; return
        }
        mutableState.update { it.copy(busy = true) }
        authJob = viewModelScope.launch {
            try {
                val result = if (request.purpose == AuthPurpose.ENABLE_LOCK) settings.setAppLockEnabled(true) else LedgerResult.Ok(Unit)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is LedgerResult.Ok -> {
                        settings.markUnlocked() // Only reachable through a successful system authentication callback.
                        mutableState.update { it.copy(request = null, busy = false, backendLocked = false, message = null) }
                    }
                    is LedgerResult.Err -> mutableState.update { it.copy(request = null, busy = false, message = "App lock could not be enabled. Review the current setting.") }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(request = null, busy = false, message = "Authentication could not be completed. Try again.") } }
        }
    }
    fun disable() {
        if (state.value.covered || state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (settings.setAppLockEnabled(false)) {
                    is LedgerResult.Ok -> mutableState.update { it.copy(busy = false) }
                    is LedgerResult.Err -> mutableState.update { it.copy(busy = false, message = "App lock could not be disabled.") }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, message = "App lock could not be disabled.") } }
        }
    }
}
