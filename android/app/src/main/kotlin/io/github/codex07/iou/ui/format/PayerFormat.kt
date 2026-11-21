package io.github.codex07.iou.ui.format

import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EntryKind

/** Presentation only: names the people who paid an entry, in stored order. */
object PayerFormat {
    fun names(item: ActivityItem, you: String): String {
        val people = if (item.kind == EntryKind.EXPENSE && item.payers.isNotEmpty()) item.payers.map { it.person } else listOf(item.paidBy)
        val names = people.map { if (it.isMe) you else it.displayName }
        return when (names.size) {
            0, 1 -> names.firstOrNull().orEmpty()
            2 -> "${names[0]} & ${names[1]}"
            3 -> "${names[0]}, ${names[1]} & 1 other"
            else -> "${names[0]}, ${names[1]} & ${names.size - 2} others"
        }
    }
}
