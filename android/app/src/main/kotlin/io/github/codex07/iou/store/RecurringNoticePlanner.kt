package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.RecurringId

/** One "due" notification: about [schedule], drawn with [title] and [text]; [silent] when only its redaction changed. */
data class DueNotice(val schedule: RecurringId, val title: String, val text: String, val silent: Boolean = false)

/** The "posted" summary of one runner pass. */
data class PostedNotice(val title: String, val text: String)

/**
 * What the notifier last drew (spec §5.7), the content of the notified file: per schedule ID the key of its due
 * notification (what it showed, and whether it was redacted), and whether a "posted" summary with details may still
 * be showing.
 */
data class NotifiedRecord(val due: Map<String, String> = emptyMap(), val detailedPosted: Boolean = false)

/**
 * What the notifier changes after a pass: due notifications to show (new, changed, or redrawn for a change of app
 * lock), schedules whose notification goes because nothing is due any more, the due group's [summary] (draw it for
 * that many schedules, 0 = remove it, null = leave it as it is), the posted summary, whether to remove a detailed
 * posted summary, and the new [notified] record.
 */
data class NoticePlan(
    val show: List<DueNotice>,
    val cancel: Set<RecurringId>,
    val summary: Int?,
    val posted: PostedNotice?,
    val cancelPosted: Boolean,
    val notified: NotifiedRecord,
)

/**
 * The notification logic of spec §5.7, without Android: one due notification per schedule with due occurrences,
 * shown again only when its oldest due occurrence, failure or backlog state changes, and redrawn silently when app
 * lock is switched on or off; texts that name no person, amount or description while app lock is on, and no detailed
 * posted summary left showing once it is on. The group summary is redrawn only when the group changes. When the
 * group shrinks to one schedule its summary is removed, and Android removes the summary's children with it, so the
 * schedule left is redrawn silently.
 */
object RecurringNoticePlanner {

    fun plan(due: List<DueOccurrence>, posted: List<PostedOccurrence>, locked: Boolean, last: NotifiedRecord): NoticePlan {
        val bySchedule = due.groupBy { it.schedule }.toSortedMap(compareBy { it.value })
        val notices = bySchedule.mapValues { (id, items) -> dueNotice(id, items, locked) }
        // "<alert key>|<digest of what is drawn>": a change of the alert key alerts; a change of only what is drawn
        // (app lock, description, amount, count, after an edit, an import or a restore) redraws silently.
        val keys = bySchedule.mapValues { (id, items) -> "${key(items.first())}|${digest(notices.getValue(id))}" }
        // Removing the group summary takes the group's notifications with it, unchanged ones included.
        val ungrouped = last.due.size > 1 && bySchedule.size == 1
        val show = bySchedule
            .filter { (id, _) -> ungrouped || last.due[id.value] != keys.getValue(id) }
            .map { (id, items) ->
                val before = last.due[id.value]?.substringBeforeLast('|')
                notices.getValue(id).copy(silent = before == key(items.first()))
            }
        val cancel = last.due.keys.map(::RecurringId).filter { it !in bySchedule }.toSet()
        val summary = when {
            bySchedule.size <= 1 -> 0
            show.isNotEmpty() || cancel.isNotEmpty() -> bySchedule.size
            else -> null
        }
        val postedNotice = posted.takeIf { it.isNotEmpty() }?.let { postedNotice(it, locked) }
        val cancelPosted = postedNotice == null && locked && last.detailedPosted
        val detailed = if (postedNotice != null) !locked else last.detailedPosted && !cancelPosted
        return NoticePlan(show, cancel, summary, postedNotice, cancelPosted, NotifiedRecord(keys.mapKeys { it.key.value }, detailed))
    }

    /** What makes a due notification worth showing again: its oldest occurrence, its failure, its backlog state. */
    private fun key(oldest: DueOccurrence): String =
        "${oldest.date}|${oldest.autoPostError?.let { it::class.simpleName }}|${oldest.confirmToResume}"

    /** A short, stable digest of a notice's title and text (`String.hashCode` is fixed by the Java spec). */
    private fun digest(notice: DueNotice): String = Integer.toHexString("${notice.title}\n${notice.text}".hashCode())

    private fun dueNotice(id: RecurringId, items: List<DueOccurrence>, locked: Boolean): DueNotice {
        val oldest = items.first()
        if (locked) {
            val text = if (items.size == 1) "1 recurring expense is due" else "${items.size} recurring expenses are due"
            return DueNotice(id, "Recurring expenses due", text)
        }
        val why = oldest.autoPostError?.let(::reason)
            ?: "Older dates need confirmation before auto-posting continues.".takeIf { oldest.confirmToResume }
        val text = "${items.size} due, oldest ${oldest.date}: ${oldest.amount}" + (why?.let { ". $it" } ?: "")
        return DueNotice(id, oldest.description, text)
    }

    private fun postedNotice(posted: List<PostedOccurrence>, locked: Boolean): PostedNotice {
        if (locked) {
            val text = if (posted.size == 1) "1 recurring expense was posted" else "${posted.size} recurring expenses were posted"
            return PostedNotice("Recurring expenses posted", text)
        }
        return PostedNotice("Recurring expenses posted", posted.joinToString("; ") { "${it.description} ${it.amount}, ${it.occurrence}" })
    }

    private fun reason(error: LedgerError): String = when (error) {
        is LedgerError.RateRequired -> "An exchange rate is needed."
        is LedgerError.MemberRemoved -> "A participant was removed from the group."
        else -> "It could not be posted automatically."
    }
}
