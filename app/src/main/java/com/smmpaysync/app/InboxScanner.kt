package com.smmpaysync.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Safety net: reads the phone's SMS inbox and feeds any new bKash payment SMS
 * into the same pipeline. Catches SMS that the live receiver missed.
 */
object InboxScanner {

    fun scan(ctx: Context, scheduleWorker: Boolean = true) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = Prefs(ctx)
        val since = prefs.inboxSince
        if (since == 0L) {
            // First run: start from now, do not import old history.
            prefs.inboxSince = System.currentTimeMillis()
            return
        }

        val cursor = try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date > ?",
                arrayOf(since.toString()),
                "date ASC"
            )
        } catch (e: Exception) {
            return
        } ?: return

        var maxDate = since
        try {
            while (cursor.moveToNext()) {
                val address = cursor.getString(0) ?: ""
                val body = cursor.getString(1) ?: ""
                val date = cursor.getLong(2)
                if (date > maxDate) maxDate = date
                val provider = Providers.byHeader(address)
                if (provider != null && body.isNotEmpty()) {
                    SmsIngest.handle(ctx, provider, body, scheduleWorker = scheduleWorker)
                }
            }
        } finally {
            cursor.close()
        }
        if (maxDate > since) prefs.inboxSince = maxDate
    }
}
