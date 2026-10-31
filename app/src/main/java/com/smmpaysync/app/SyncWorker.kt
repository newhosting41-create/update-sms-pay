package com.smmpaysync.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val app = applicationContext
        return try {
            // Recover payment SMS that the live receiver missed while the app process was idle.
            // The inbox scanner does not enqueue nested jobs; this worker sends the rows itself.
            InboxScanner.scan(app, scheduleWorker = false)
            when (SyncEngine.run(app)) {
                SyncEngine.Outcome.RETRY -> {
                    Notifier.update(app)
                    Result.retry()
                }
                SyncEngine.Outcome.DONE -> {
                    Notifier.update(app)
                    if (Db.get(app).pending().isNotEmpty()) enqueue(app)
                    Result.success()
                }
                SyncEngine.Outcome.NOT_CONFIGURED -> {
                    Notifier.update(app)
                    Result.success()
                }
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private fun constraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("sync_now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /**
         * Receiver fallback is delayed briefly so the SMS_RECEIVED fast path gets first chance.
         * It remains persisted if the process is killed or the immediate HTTP request fails.
         */
        fun enqueueReceiverFallback(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints())
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(
                    "sms_receiver_fallback",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    req
                )
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
