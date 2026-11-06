package io.github.codex07.iou.ui.viewmodel

import app.cash.turbine.test
import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.*
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.ui.settings.*
import io.github.codex07.iou.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSecurityTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }
    private class MemoryTheme(var stored: String? = null, var writable: Boolean = true) : ThemeStorage {
        override fun read() = stored
        override fun write(value: String): Boolean { if (!writable) return false; stored = value; return true }
    }
    private fun vm(a: Apis) = AppSettingsViewModel(a.setup, a.settings, a.people, a.backup, ThemePreferences(MemoryTheme()), DataServices.Simulated)

    @Test fun `setup validates fields and never reinitializes an existing ledger`() = runTest(dispatcher) {
        val a = FakeApis.create(FakeApis.store()); val vm = vm(a)
        vm.completeSetup("", "EUR"); runCurrent(); assertTrue(vm.action.value.error is LedgerError.InvalidField)
        vm.completeSetup("New owner", "XYZ"); runCurrent(); assertTrue(vm.action.value.error is LedgerError.UnknownCurrency)
        assertEquals(SetupState.NeedsSetup, a.setup.observeSetupState().first())
        vm.completeSetup("New owner", "SEK"); runCurrent(); val owner = a.setup.observeSetupState().first() as SetupState.Ready
        assertEquals("New owner", owner.me.displayName); assertEquals(CurrencyCode("SEK"), a.settings.observeSettings().first().homeCurrency)
        vm.completeSetup("Replacement identity", "EUR"); runCurrent()
        assertEquals(LedgerError.CannotModifyMe, vm.action.value.error)
        assertEquals(owner, a.setup.observeSetupState().first())
    }

    @Test fun `seeded setup is preserved and settings observations update from backend writes`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a)
        vm.page.test {
            assertNull(awaitItem()); val first = awaitItem()!!
            assertTrue(first.setup is SetupState.Ready)
            vm.setCurrency("JPY"); runCurrent()
            var next = awaitItem()!!
            while (next.settings.homeCurrency.code != "JPY") next = awaitItem()!!
            assertEquals("JPY", next.settings.homeCurrency.code)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `home currency changes leave original amounts currencies and FX snapshots unchanged`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val before = a.ledger.observeActivity().first()
        val snapshots = before.associate { it.entryId to a.ledger.observeEntry(it.entryId).first()!!.fx }
        val vm = vm(a); vm.setCurrency("KWD"); runCurrent()
        assertEquals(CurrencyCode("KWD"), a.settings.observeSettings().first().homeCurrency)
        assertEquals(before, a.ledger.observeActivity().first())
        before.forEach { assertEquals(snapshots[it.entryId], a.ledger.observeEntry(it.entryId).first()!!.fx) }
    }

    @Test fun `profile display name uses person API without changing permanent identity`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a)
        val before = (a.setup.observeSetupState().first() as SetupState.Ready).me
        val token = vm.profileToken()!!
        vm.updateProfile("Owner name", token); runCurrent()
        val after = (a.setup.observeSetupState().first() as SetupState.Ready).me
        assertEquals(before.id, after.id); assertTrue(after.isMe); assertEquals("Owner name", after.displayName)
        vm.updateProfile("Stale overwrite", token); runCurrent(); assertTrue(vm.action.value.error is LedgerError.StaleVersion)
    }

    @Test fun `theme survives repository recreation and write failure retains the current value`() {
        val store = MemoryTheme(); val first = ThemePreferences(store)
        assertEquals(ThemePreference.SYSTEM, first.theme.value)
        assertTrue(first.set(ThemePreference.DARK))
        assertEquals(ThemePreference.DARK, ThemePreferences(store).theme.value)
        store.writable = false; assertFalse(first.set(ThemePreference.LIGHT)); assertEquals(ThemePreference.DARK, first.theme.value)
        assertEquals(ThemePreference.SYSTEM, ThemePreferences(MemoryTheme("unknown")).theme.value)
    }

    @Test fun `screen protection encryption preference and all backend timeout options update`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a)
        vm.screenProtection(true); runCurrent(); assertTrue(a.settings.observeSettings().first().screenProtection)
        vm.backupEncryption(false); runCurrent(); assertFalse(a.settings.observeSettings().first().backupEncryptionDefault)
        for (timeout in LockTimeout.entries) { vm.setTimeout(timeout); runCurrent(); assertEquals(timeout, a.settings.observeSettings().first().lockTimeout) }
    }

    @Test fun `enabling lock requires a secure device and available system authentication`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = SecurityViewModel(a.settings, a.backup); runCurrent(); vm.foreground()
        vm.begin(AuthPurpose.ENABLE_LOCK, false, true); assertNull(vm.state.value.request)
        vm.begin(AuthPurpose.ENABLE_LOCK, true, false); assertNull(vm.state.value.request)
        assertFalse(a.settings.observeSettings().first().appLockEnabled)
        vm.begin(AuthPurpose.ENABLE_LOCK, true, true); assertNotNull(vm.state.value.request)
        val id = vm.state.value.request!!.id; vm.begin(AuthPurpose.ENABLE_LOCK, true, true)
        assertEquals(id, vm.state.value.request!!.id)
        vm.launched(id); assertTrue(vm.state.value.request!!.launched)
    }

    @Test fun `only successful system result marks unlocked and cancellation keeps protected UI inaccessible`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.settings.setAppLockEnabled(true); var unlocks = 0
        val settings = object : SettingsApi by a.settings { override fun markUnlocked() { unlocks++; a.settings.markUnlocked() } }
        val vm = SecurityViewModel(settings, a.backup); runCurrent(); vm.foreground()
        assertTrue(vm.state.value.covered)
        vm.begin(AuthPurpose.UNLOCK, true, true); val first = vm.state.value.request!!.id
        vm.failedAttempt(); assertEquals(0, unlocks)
        vm.result(first, false); runCurrent(); assertTrue(vm.state.value.covered); assertEquals(0, unlocks)
        vm.begin(AuthPurpose.UNLOCK, true, true); val second = vm.state.value.request!!.id
        vm.result(second, true); runCurrent(); assertFalse(vm.state.value.covered); assertEquals(1, unlocks)
        vm.result(first, true); runCurrent(); assertEquals(1, unlocks)
    }

    @Test fun `successful enabling authenticates first then enables lock and allows protected content`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = SecurityViewModel(a.settings, a.backup); runCurrent(); vm.foreground()
        vm.begin(AuthPurpose.ENABLE_LOCK, true, true)
        assertFalse(a.settings.observeSettings().first().appLockEnabled)
        vm.result(vm.state.value.request!!.id, true); runCurrent()
        assertTrue(a.settings.observeSettings().first().appLockEnabled); assertFalse(vm.state.value.covered)
    }

    @Test fun `foreground fallback is conservative for every saved timeout without a competing timer`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.settings.setAppLockEnabled(true)
        val vm = SecurityViewModel(a.settings, a.backup); runCurrent(); vm.foreground()
        for (timeout in LockTimeout.entries) {
            a.settings.setLockTimeout(timeout); runCurrent()
            vm.begin(AuthPurpose.UNLOCK, true, true); vm.result(vm.state.value.request!!.id, true); runCurrent()
            assertFalse(vm.state.value.covered)
            vm.background(); assertTrue(vm.state.value.covered); assertTrue(vm.state.value.secureWindow)
            vm.foreground(); assertTrue(vm.state.value.locked)
        }
    }

    @Test fun `security loading and screen protection fail closed and toggle effective secure flag`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = SecurityViewModel(a.settings, a.backup)
        assertTrue(vm.state.value.covered); assertTrue(vm.state.value.secureWindow)
        runCurrent(); vm.foreground(); assertFalse(vm.state.value.secureWindow)
        a.settings.setScreenProtection(true); runCurrent(); assertTrue(vm.state.value.secureWindow)
        a.settings.setScreenProtection(false); runCurrent(); assertFalse(vm.state.value.secureWindow)
    }

    @Test fun `ledger replacement cancels an old authentication request and ignores its callback`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.settings.setAppLockEnabled(true); var unlocks = 0
        val settings = object : SettingsApi by a.settings { override fun markUnlocked() { unlocks++ } }
        val vm = SecurityViewModel(settings, a.backup); runCurrent(); vm.foreground()
        vm.begin(AuthPurpose.UNLOCK, true, true); val id = vm.state.value.request!!.id
        a.backup.createBackup(DocumentRef("replace"), null); a.backup.restoreBackup(DocumentRef("replace"), null); runCurrent()
        assertNull(vm.state.value.request); vm.result(id, true); runCurrent()
        assertEquals(0, unlocks); assertTrue(vm.state.value.covered)
    }

    @Test fun `informational banner body has readable contrast in both palettes`() {
        fun luminance(c: androidx.compose.ui.graphics.Color): Double {
            fun channel(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        fun background(primary: androidx.compose.ui.graphics.Color, base: androidx.compose.ui.graphics.Color, alpha: Float) = androidx.compose.ui.graphics.Color(
            primary.red * alpha + base.red * (1 - alpha), primary.green * alpha + base.green * (1 - alpha), primary.blue * alpha + base.blue * (1 - alpha))
        for ((text, surface, blue, alpha) in listOf(
            arrayOf(LightOnBackground, LightBackground, LightSecondary, 0.12f),
            arrayOf(DarkOnBackground, DarkBackground, DarkSecondary, 0.13f))) {
            val foreground = luminance(text as androidx.compose.ui.graphics.Color)
            val back = luminance(background(blue as androidx.compose.ui.graphics.Color, surface as androidx.compose.ui.graphics.Color, (alpha as Float) * 0.6f))
            assertTrue((maxOf(foreground, back) + 0.05) / (minOf(foreground, back) + 0.05) >= 4.5)
        }
    }
}
