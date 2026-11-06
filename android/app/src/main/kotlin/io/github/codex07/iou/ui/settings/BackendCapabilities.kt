package io.github.codex07.iou.ui.settings

/** Verified against both local flavor bindings. Change only when actual services/policy replace FakeApis. */
object BackendCapabilities {
    const val simulatedDataServices = true
    const val lifecycleTimeoutPolicy = false
    const val dataNotice = "Simulation only: both local flavors use in-memory services. Backup/export write no financial data; restore/import simulate outcomes. Documents selected here are not usable financial backups. Use test passphrases only."
    const val lockNotice = "The current backend does not implement background timeout tracking. This build conservatively requires system authentication whenever you return, regardless of the saved timeout. Security preferences are also in-memory and reset on process restart; this is not a production security backend."
}
