package io.github.codex07.iou.ui.navigation

import androidx.annotation.StringRes
import io.github.codex07.iou.R

/** Primary shell destinations, including backend-driven repayment suggestions. */
enum class Destination(@get:StringRes val title: Int) {
    Overview(R.string.nav_overview), People(R.string.nav_people), Groups(R.string.nav_groups),
    Activity(R.string.nav_activity), Settlements(R.string.nav_settle),
}
