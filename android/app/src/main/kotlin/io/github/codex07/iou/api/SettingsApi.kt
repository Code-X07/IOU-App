package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow

interface SettingsApi {
    fun observeSettings(): Flow<Settings>

    suspend fun setHomeCurrency(currency: CurrencyCode): LedgerResult<Unit>

    /** The UI checks for a secure lock screen before enabling (spec §9). */
    suspend fun setAppLockEnabled(enabled: Boolean): LedgerResult<Unit>

    suspend fun setLockTimeout(timeout: LockTimeout): LedgerResult<Unit>

    suspend fun setScreenProtection(enabled: Boolean): LedgerResult<Unit>

    suspend fun setBackupEncryptionDefault(enabled: Boolean): LedgerResult<Unit>

    /** True while the lock screen must cover all content (cold start, timeout in background). */
    fun observeLockRequired(): Flow<Boolean>

    /** Called by the UI after a successful `BiometricPrompt`. */
    fun markUnlocked()
}
