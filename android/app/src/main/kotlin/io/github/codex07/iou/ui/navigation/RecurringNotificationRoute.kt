package io.github.codex07.iou.ui.navigation

import io.github.codex07.iou.ledger.model.RecurringId

data class RecurringNotificationRoute(val schedule: RecurringId?) {
    fun open(stack: List<String>): List<String> {
        val route = "due:${schedule?.value.orEmpty()}"
        if (stack.lastOrNull() == route) return stack
        val retained = if (stack.lastOrNull()?.startsWith("due:") == true) stack.dropLast(1) else stack
        return retained.filterNot { it == route } + route
    }

    companion object {
        fun fromExtras(open: String?, schedule: String?): RecurringNotificationRoute? {
            if (open != "due") return null
            val id = schedule?.takeIf { value -> value.isNotBlank() && value.none { it == ':' || it.isISOControl() } }
            return RecurringNotificationRoute(id?.let(::RecurringId))
        }
    }
}
