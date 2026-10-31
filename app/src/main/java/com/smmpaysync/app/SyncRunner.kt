package com.smmpaysync.app

import android.content.Context
import java.util.concurrent.Executors

/** One pass: recover inbox SMS and send pending events to the event-ingest endpoint. */
object SyncRunner {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sync-runner").also { it.isDaemon = true }
    }

    @Synchronized
    fun runOnce(ctx: Context) {
        val app = ctx.applicationContext
        try {
            InboxScanner.scan(app, scheduleWorker = false)
        } catch (e: Exception) {
        }
        try {
            if (UiUtil.isOnline(app)) {
                SyncEngine.run(app)
            }
        } catch (e: Exception) {
        }
        Notifier.update(app)
    }

    fun trigger(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { runOnce(app) }
    }
}
