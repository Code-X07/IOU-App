package io.github.codex07.iou.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePreference { SYSTEM, LIGHT, DARK }
interface ThemeStorage { fun read(): String?; fun write(value: String): Boolean }
/** Isolated device UI preference: no ledger settings or secrets are copied into this store. */
class ThemePreferences(private val storage: ThemeStorage) {
    private val mutableTheme = MutableStateFlow(ThemePreference.entries.firstOrNull { it.name == storage.read() } ?: ThemePreference.SYSTEM)
    val theme = mutableTheme.asStateFlow()
    fun set(value: ThemePreference): Boolean {
        if (!try { storage.write(value.name) } catch (_: Exception) { false }) return false
        mutableTheme.value = value; return true
    }
}
