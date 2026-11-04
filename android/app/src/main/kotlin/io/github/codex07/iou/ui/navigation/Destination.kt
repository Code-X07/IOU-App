package io.github.codex07.iou.ui.navigation

import androidx.annotation.StringRes
import io.github.codex07.iou.R

/** Phase 1 contains one implemented dashboard; every other destination is deliberately a placeholder. */
enum class Destination(@get:StringRes val title: Int) {
    Overview(R.string.nav_overview), People(R.string.nav_people), Groups(R.string.nav_groups),
    Activity(R.string.nav_activity), Settlements(R.string.nav_settle),
}
