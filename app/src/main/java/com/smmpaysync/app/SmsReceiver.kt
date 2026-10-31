package com.smmpaysync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) return

        val app = context.applicationContext
        val from = msgs[0].originatingAddress ?: ""
        Prefs(app).noteSmsBroadcast(from)
        val provider = Providers.byHeader(from)
        if (provider == null) {
            Prefs(app).note("SMS from '$from' ignored (sender not configured)")
            return
        }
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val parsed = SmsParsers.parse(provider, body)

        // Keep the broadcast alive only for a short, bounded immediate send attempt.
        val pending = goAsync()
        Thread({
            try {
                val prefs = Prefs(app)
                val providerEnabled = prefs.providerEnabled(provider)
                val saved = SmsIngest.handle(
                    app,
                    provider,
                    body,
                    fromSmsReceiver = true,
                    scheduleWorker = false
                )
                val pendingEvent = parsed
                    ?.let { Db.get(app).find(it.trxId) }
                    ?.takeIf { it.status == St.PENDING }

                if (providerEnabled && pendingEvent != null) {
                    // Persist a network-constrained backup before any network request.
                    try {
                        SyncWorker.enqueueReceiverFallback(app)
                    } catch (e: Exception) {
                        prefs.note("SMS queued; background retry could not be scheduled: ${e.message}")
                    }

                    val outcome = when {
                        !prefs.isConfigured -> SyncEngine.Outcome.NOT_CONFIGURED
                        !runCatching { UiUtil.isOnline(app) }.getOrDefault(false) ->
                            SyncEngine.Outcome.RETRY
                        else -> SyncEngine.run(
                            app,
                            onlyTrxId = pendingEvent.trxId,
                            connectTimeoutMs = 2500,
                            readTimeoutMs = 3500,
                            returnIfBusy = true
                        )
                    }

                    val latest = Db.get(app).find(pendingEvent.trxId)
                    val note = when {
                        latest?.status == St.SYNCED -> "${provider.label} SMS sent to server immediately"
                        latest?.status == St.DUPLICATE -> "${provider.label} TrxID already exists on server"
                        latest?.status == St.FLAGGED -> "${provider.label} SMS sent; server flagged it for review"
                        latest?.status == St.REJECTED ->
                            "${provider.label} SMS rejected by server: ${latest.error ?: "check Pending"}"
                        latest?.status == St.FAILED ->
                            "${provider.label} SMS upload failed: ${latest.error ?: "check Pending"}"
                        outcome == SyncEngine.Outcome.NOT_CONFIGURED ->
                            "${provider.label} SMS saved; configure the server in Settings"
                        outcome == SyncEngine.Outcome.RETRY ->
                            "${provider.label} SMS saved; immediate send failed/busy, retry queued"
                        else -> "${provider.label} SMS saved; waiting for background retry"
                    }
                    prefs.note(note)
                } else if (saved) {
                    prefs.note("${provider.label} SMS saved locally; it was not eligible for immediate upload")
                }

                // Service is only a persistent notification/monitor; upload above does not depend on it.
                MonitorService.ensure(app)
                Notifier.update(app)
            } catch (e: Exception) {
                Prefs(app).note("error handling received SMS: ${e.message}")
                try {
                    Notifier.update(app)
                } catch (_: Exception) {
                }
            } finally {
                pending.finish()
            }
        }, "sms-receive-upload").start()
    }
}
