package io.github.codex07.iou.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.codex07.iou.MainActivity
import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.NotifiedRecord
import io.github.codex07.iou.store.PostedOccurrence
import io.github.codex07.iou.store.RecurringNoticePlanner
import io.github.codex07.iou.store.RecurringNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The real flavor's notifications (spec §5.7): "due" per schedule, one "posted" summary per pass, redacted while app
 * lock is on ([settings]). Switching app lock on or off makes the runner report at once ([redraw]), which redraws
 * the due notifications silently and, when it goes on, removes a detailed posted summary. Without the notification
 * permission, or on a blocked channel, nothing is shown and nothing is recorded as shown, so what is due appears again
 * once it can be drawn.
 */
class AndroidRecurringNotices(
    private val context: Context,
    private val notified: RecurringNotifiedFile,
    private val settings: StateFlow<DeviceSettings>,
) : RecurringNotices {

    override val redraw: Flow<Unit> = settings.map { it.appLockEnabled }.distinctUntilChanged().drop(1).map { }

    override suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>) = withContext(Dispatchers.IO) {
        val manager = NotificationManagerCompat.from(context)
        val last = notified.read()
        val plan = RecurringNoticePlanner.plan(due, posted, settings.value.appLockEnabled, last)
        plan.cancel.forEach { manager.cancel(it.value, DUE_ID) }
        // Before the redraws: removing the summary removes its children too, and the planner redraws the one left.
        if (plan.summary == 0) manager.cancel(GROUP_SUMMARY_TAG, DUE_ID)
        if (plan.cancelPosted) manager.cancel(POSTED_TAG, POSTED_ID)
        // While nothing can be shown, nothing is recorded as shown: Android may remove what was showing (a revoked
        // permission, a blocked channel), so the first pass that can draw again shows everything that is due.
        val undrawn = NotifiedRecord()
        if (!manager.areNotificationsEnabled()) {
            notified.write(undrawn)
            return@withContext
        }
        createChannels(manager)
        fun blocked(channel: String) = manager.getNotificationChannelCompat(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE
        val dueBlocked = blocked(DUE_CHANNEL)
        val postedBlocked = blocked(POSTED_CHANNEL)
        try {
            if (!dueBlocked) {
                // Every schedule's notification opens that schedule's due dates; only the group summary opens them all.
                for (notice in plan.show) {
                    manager.notify(notice.schedule.value, DUE_ID, build(DUE_CHANNEL, notice.title, notice.text, notice.schedule, notice.silent))
                }
                plan.summary?.takeIf { it > 1 }?.let { manager.notify(GROUP_SUMMARY_TAG, DUE_ID, summary(it)) }
            }
            if (!postedBlocked) {
                plan.posted?.let { manager.notify(POSTED_TAG, POSTED_ID, build(POSTED_CHANNEL, it.title, it.text, null, silent = false)) }
            }
            notified.write(
                NotifiedRecord(
                    due = if (dueBlocked) emptyMap() else plan.notified.due,
                    detailedPosted = !postedBlocked && plan.notified.detailedPosted,
                ),
            )
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post: record nothing as shown.
            notified.write(undrawn)
        }
    }

    private fun build(channel: String, title: String, text: String, schedule: RecurringId?, silent: Boolean) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setGroup(if (channel == DUE_CHANNEL) DUE_GROUP else null)
            .setSilent(silent)
            .setContentIntent(openDue(schedule))
            .build()

    /** The due group's summary. It never alerts: only a new or changed due notification does. */
    private fun summary(count: Int) = NotificationCompat.Builder(context, DUE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("Recurring expenses due")
        .setContentText("$count schedules have due dates")
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setGroup(DUE_GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(openDue(null))
        .build()

    /** Opens the due list: `iou.open = "due"`, plus the schedule when the notification is about one. */
    private fun openDue(schedule: RecurringId?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, OPEN_DUE)
        schedule?.let { intent.putExtra(EXTRA_SCHEDULE, it.value) }
        val request = schedule?.value?.hashCode() ?: 0
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(DUE_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Recurring expenses due").build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(POSTED_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Recurring expenses posted").setSound(null, null).build(),
        )
    }

    companion object {
        const val EXTRA_OPEN = "iou.open"
        const val OPEN_DUE = "due"
        const val EXTRA_SCHEDULE = "iou.schedule"
        private const val DUE_CHANNEL = "recurring-due"
        private const val POSTED_CHANNEL = "recurring-posted"
        private const val DUE_GROUP = "io.github.codex07.iou.DUE"
        private const val GROUP_SUMMARY_TAG = "due-summary"
        private const val POSTED_TAG = "posted"
        private const val DUE_ID = 1
        private const val POSTED_ID = 2
    }
}
