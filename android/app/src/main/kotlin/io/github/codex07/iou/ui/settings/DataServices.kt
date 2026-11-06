package io.github.codex07.iou.ui.settings

/** What the bound backend does with backups, exports and imports. Each flavor's BackendModule provides one. */
data class DataServices(val simulated: Boolean, val notice: String) {
    companion object {
        val Simulated = DataServices(true, "Simulation only: this build uses in-memory demo services. Backup and export write no financial data; restore and import simulate their outcomes. Documents selected here are not usable financial backups. Use test passphrases only.")
        val Real = DataServices(false, "Backups and exports are written to the document you choose and are not shared automatically. Restore and import change the data stored on this device.")
    }
}
