package com.smmpaysync.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

/** Single place that sends queued events to the server (used by workers, UI, and SMS receiver). */
object SyncEngine {

    enum class Outcome { DONE, RETRY, NOT_CONFIGURED }
    private val runLock = ReentrantLock()

    /**
     * Send pending events. Receiver calls may select one TrxID, use short timeouts,
     * and return immediately if another sync already owns the lock; WorkManager is the fallback.
     */
    fun run(
        ctx: Context,
        onlyTrxId: String? = null,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 20000,
        returnIfBusy: Boolean = false
    ): Outcome {
        val app = ctx.applicationContext
        val acquired = if (returnIfBusy) runLock.tryLock() else {
            runLock.lock()
            true
        }
        if (!acquired) {
            Prefs(app).lastResult = "Sync already running; queued for retry"
            return Outcome.RETRY
        }

        try {
            val prefs = Prefs(app)
            if (!prefs.isConfigured) return Outcome.NOT_CONFIGURED
            val db = Db.get(app)
            val events = if (onlyTrxId == null) {
                db.pending()
            } else {
                db.find(onlyTrxId)?.takeIf { it.status == St.PENDING }?.let { listOf(it) }
                    ?: emptyList()
            }

            for (e in events) {
                val provider = Providers.byKey(e.provider)
                val body = JSONObject()
                    .put("action", "event")
                    .put("sender", provider.serverSender)
                    .put("provider", provider.key)
                    .put("raw_sms", e.raw)
                    .put("local_event_id", e.localId)
                    .toString()

                val res = try {
                    Http.post(
                        prefs.url,
                        prefs.token,
                        prefs.secret,
                        body,
                        connectTimeoutMs,
                        readTimeoutMs
                    )
                } catch (ex: Exception) {
                    prefs.lastResult = "Network error"
                    return Outcome.RETRY
                }
                val code = res.first
                val text = res.second

                when {
                    code == 200 -> {
                        val json = try {
                            JSONObject(text)
                        } catch (x: Exception) {
                            null
                        }
                        if (json == null || !json.optBoolean("success", false)) {
                            db.mark(e.localId, St.FAILED, "Unexpected server response")
                            prefs.lastResult = "Unexpected response"
                        } else {
                            val newStatus = when (json.optString("status")) {
                                "duplicate" -> St.DUPLICATE
                                "flagged" -> St.FLAGGED
                                else -> St.SYNCED
                            }
                            db.mark(e.localId, newStatus, null)
                            prefs.lastSync = System.currentTimeMillis()
                            prefs.lastResult = "OK"
                        }
                    }
                    code == 401 || code == 403 -> {
                        db.mark(e.localId, St.FAILED, "Invalid token/secret (HTTP $code)")
                        prefs.lastResult = "Invalid token/secret"
                        return Outcome.DONE // user must fix settings, then tap Send
                    }
                    code == 422 -> {
                        val msg = try {
                            JSONObject(text).optString("message")
                        } catch (x: Exception) {
                            ""
                        }
                        db.mark(e.localId, St.REJECTED, if (msg.isEmpty()) "Rejected by server" else msg)
                        prefs.lastResult = "OK"
                    }
                    else -> {
                        prefs.lastResult = "Server error HTTP $code"
                        return Outcome.RETRY
                    }
                }
            }
            return Outcome.DONE
        } finally {
            runLock.unlock()
        }
    }

    /** Ask the server which delivered TrxIDs were already used on the website. */
    fun pollStatus(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.isConfigured) return
        val db = Db.get(app)
        val list = db.listByStatus(St.onServer, 50)
        if (list.isEmpty()) return

        val arr = JSONArray()
        for (e in list) arr.put(e.trxId)
        val body = JSONObject().put("action", "status").put("trx_ids", arr).toString()

        val res = try {
            Http.post(prefs.url, prefs.token, prefs.secret, body)
        } catch (e: Exception) {
            prefs.lastResult = "Status check: network error"
            return
        }
        if (res.first != 200) {
            prefs.lastResult = "Status check failed: HTTP ${res.first}"
            return
        }
        val json = try {
            JSONObject(res.second)
        } catch (e: Exception) {
            prefs.lastResult = "Status check: server did not return JSON"
            return
        }
        if (!json.optBoolean("success", false)) {
            prefs.lastResult = "Status check: " + json.optString("message", "server rejected request")
            return
        }
        val items = json.optJSONObject("items") ?: return
        for (e in list) {
            if (items.optString(e.trxId) == "used") db.mark(e.localId, St.USED, null)
        }
        prefs.lastResult = "OK"
    }

    fun ping(ctx: Context): String {
        val prefs = Prefs(ctx.applicationContext)
        if (!prefs.isConfigured) return "Save URL, Token and Secret first"
        return try {
            val r = Http.post(prefs.url, prefs.token, prefs.secret, "{\"action\":\"ping\"}")
            if (r.first == 200) {
                prefs.lastResult = "OK"
                "Connected successfully"
            } else {
                prefs.lastResult = "HTTP ${r.first}"
                "Failed: HTTP ${r.first} ${r.second.take(100)}"
            }
        } catch (e: Exception) {
            prefs.lastResult = "Network error"
            "Error: ${e.message}"
        }
    }
}
