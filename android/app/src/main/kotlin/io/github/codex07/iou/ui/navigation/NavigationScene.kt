package io.github.codex07.iou.ui.navigation

/** Presentation metadata only. Never retains outgoing records or runs a ledger operation. */
data class NavigationScene(val tab: String, val stack: List<String>, val generation: String?) {
    val route: String? get() = stack.lastOrNull()
    val key: String get() = "${route ?: tab}@$generation"
    val isSettings: Boolean get() = route == "settings:" || route?.startsWith("form-data:") == true
    val isForm: Boolean get() = route?.startsWith("form-") == true
    val showBottomBar: Boolean get() = !isForm && !isSettings
    val showAdd: Boolean get() = stack.isEmpty()
}

enum class PageMotion(val durationMillis: Int, val offsetDp: Float) {
    NONE(0, 0f), TAB(210, 12f), FORWARD(280, 28f), BACK(240, -24f);

    companion object {
        fun between(previous: NavigationScene?, next: NavigationScene): PageMotion = when {
            previous == null || previous.generation != next.generation || previous.key == next.key -> NONE
            next.stack.size < previous.stack.size -> BACK
            next.stack.isNotEmpty() -> FORWARD
            else -> TAB
        }
    }
}
