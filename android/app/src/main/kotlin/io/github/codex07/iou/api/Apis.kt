package io.github.codex07.iou.api

/** All eight interfaces of one implementation; used by contract tests and Compose previews. */
data class Apis(
    val setup: SetupApi,
    val people: PeopleApi,
    val groups: GroupsApi,
    val ledger: LedgerApi,
    val fx: FxApi,
    val backup: BackupApi,
    val importExport: ImportExportApi,
    val settings: SettingsApi,
)
