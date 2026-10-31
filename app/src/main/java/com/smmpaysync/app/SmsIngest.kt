package com.smmpaysync.app

import android.content.Context
import java.util.UUID

object SmsIngest {

    /** Save one supported provider SMS. Same logic for live receiver and inbox scan. */
    fun handle(
        ctx: Context,
        provider: Provider,
        body: String,
        fromSmsReceiver: Boolean = false,
        scheduleWorker: Boolean = true
    ): Boolean {
        val prefs = Prefs(ctx)
        if (!prefs.providerEnabled(provider)) {
            prefs.note("${provider.label} SMS seen, but its provider switch is OFF")
            return false
        }
        val db = Db.get(ctx)
        val p = SmsParsers.parse(provider, body)

        if (p == null) {
            if (!body.contains("TrxID", ignoreCase = true)) {
                prefs.note("${provider.label} SMS ignored (not a payment message)")
                return false
            }
            // Payment-looking SMS we could not read: keep it visible in Pending.
            val saved = db.insert(
                Ev(
                    localId = UUID.randomUUID().toString(),
                    provider = provider.key,
                    trxId = "UNREAD-" + Integer.toHexString(body.hashCode()),
                    amount = 0.0,
                    sender = "",
                    smsTime = "",
                    raw = body,
                    status = St.FAILED,
                    error = "Could not read this SMS. Copy it and use Send SMS Manually.",
                    createdAt = System.currentTimeMillis()
                )
            )
            prefs.note(if (saved) "payment SMS could not be read, kept in Pending" else "unreadable SMS already kept")
            if (saved) Notifier.update(ctx)
            return saved
        }

        val saved = db.insert(
            Ev(
                localId = UUID.randomUUID().toString(),
                provider = provider.key,
                trxId = p.trxId,
                amount = p.amount,
                sender = p.sender,
                smsTime = p.smsTime,
                raw = body,
                status = St.PENDING,
                error = null,
                createdAt = System.currentTimeMillis()
            )
        )
        prefs.note(if (saved) "payment SMS saved, TrxID ${p.trxId}" else "already saved, TrxID ${p.trxId}")
        if (saved) {
            Notifier.update(ctx)
            // Persist the network-constrained job before doing anything that can take time.
            // SMS broadcasts have a short execution window; never block them on HTTP.
            if (scheduleWorker) SyncWorker.enqueue(ctx)
            if (scheduleWorker && !fromSmsReceiver) SyncRunner.trigger(ctx)
        }
        return saved
    }
}
