package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ui.settings.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SettingsPage(val setup: SetupState, val settings: Settings, val reminder: BackupReminder, val undoRestore: Boolean)
data class SettingsActionState(val busy: Boolean = false, val error: LedgerError? = null, val message: String? = null)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val setup: SetupApi, private val settings: SettingsApi, private val people: PeopleApi,
    backup: BackupApi, private val preferences: ThemePreferences,
) : ViewModel() {
    private val mutableAction = MutableStateFlow(SettingsActionState())
    private val refresh = MutableStateFlow(0)
    val generation = refresh.flatMapLatest { backup.observeLedgerGeneration().catch { mutableAction.value = SettingsActionState(message = "Ledger status is unavailable. Reopen the app or retry.") } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun retry() { refresh.update { it + 1 } }
    val page = refresh.flatMapLatest { combine(setup.observeSetupState(), settings.observeSettings(), backup.observeBackupReminder(), backup.observeUndoRestoreAvailable()) {
        owner, options, reminder, undo -> SettingsPage(owner, options, reminder, undo)
    }.catch { mutableAction.value = SettingsActionState(message = "Settings could not be loaded. Close and reopen the app.") }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val action = mutableAction.asStateFlow()
    val theme = preferences.theme
    private fun perform(block: suspend () -> LedgerResult<Unit>) {
        if (action.value.busy) return
        mutableAction.value = SettingsActionState(busy = true)
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is LedgerResult.Ok -> mutableAction.value = SettingsActionState(message = "Saved")
                    is LedgerResult.Err -> mutableAction.value = SettingsActionState(error = result.error)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableAction.value = SettingsActionState(message = "The update could not be confirmed. Review the current settings before trying again.") }
        }
    }
    fun completeSetup(name: String, currency: String) = perform {
        if (setup.observeSetupState().first() is SetupState.Ready) return@perform LedgerResult.Err(LedgerError.CannotModifyMe)
        if (name.isBlank()) return@perform LedgerResult.Err(LedgerError.InvalidField("displayName"))
        when (val code = Inputs.currency(currency)) {
            is LedgerResult.Err -> code
            is LedgerResult.Ok -> setup.completeSetup(name.trim(), code.value).map { Unit }
        }
    }
    fun setCurrency(value: String) = perform { Inputs.currency(value).let { parsed -> when (parsed) {
        is LedgerResult.Err -> parsed
        is LedgerResult.Ok -> settings.setHomeCurrency(parsed.value)
    } } }
    fun updateProfile(name: String, token: EditToken) = perform {
        if (name.isBlank()) return@perform LedgerResult.Err(LedgerError.InvalidField("displayName"))
        val owner = (setup.observeSetupState().first() as? SetupState.Ready)?.me ?: return@perform LedgerResult.Err(LedgerError.InvalidField("displayName"))
        val detail = people.observePerson(owner.id).first() ?: return@perform LedgerResult.Err(LedgerError.NotFound(owner.id.value))
        people.updatePerson(owner.id, PersonDraft(name.trim(), detail.note, detail.defaultCurrency), token)
    }
    suspend fun profileToken(): EditToken? = try { (setup.observeSetupState().first() as? SetupState.Ready)?.me?.let { people.observePerson(it.id).first()?.token } }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { mutableAction.value = SettingsActionState(message = "The profile could not be loaded. Reopen the edit dialog to retry."); null }
    fun setTimeout(value: LockTimeout) = perform { settings.setLockTimeout(value) }
    fun screenProtection(value: Boolean) = perform { settings.setScreenProtection(value) }
    fun backupEncryption(value: Boolean) = perform { settings.setBackupEncryptionDefault(value) }
    fun setTheme(value: ThemePreference) {
        mutableAction.value = if (preferences.set(value)) SettingsActionState(message = "Appearance saved on this device")
            else SettingsActionState(message = "Appearance could not be saved")
    }
}
