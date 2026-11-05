package io.github.codex07.iou.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * The daily rate refresh (spec §4.5, §8): needs a network, retries with exponential backoff, never reports
 * anything. It reaches the app's single [RealBackend] through a Hilt entry point.
 */
class RateRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Backend {
        fun backend(): RealBackend
    }

    override suspend fun doWork(): Result {
        val backend = EntryPointAccessors.fromApplication(applicationContext, Backend::class.java).backend()
        return if (backend.refreshRates()) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "fx-rate-refresh"

        /** Schedules the refresh once; an existing schedule is kept, so app starts don't reset its timing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RateRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
