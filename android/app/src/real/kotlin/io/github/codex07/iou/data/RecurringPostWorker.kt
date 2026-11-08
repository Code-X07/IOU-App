package io.github.codex07.iou.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Background auto-posting (spec §5.7): every 6 hours, no constraints, one runner pass (which also updates the
 * notifications). It waits for the start-up load like every caller and for a restore through the store's lock.
 * Failures are kept as each schedule's `autoPostError`, so the work itself always succeeds.
 */
class RecurringPostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, RateRefreshWorker.Backend::class.java).backend().runner.run()
        return Result.success()
    }

    companion object {
        const val NAME = "recurring-post"

        /** Schedules the pass once; an existing schedule is kept, so app starts don't reset its timing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecurringPostWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
