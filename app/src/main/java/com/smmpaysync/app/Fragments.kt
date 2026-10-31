package com.smmpaysync.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal fun Fragment.toast(msg: String) {
    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}

internal fun hasSms(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

/* ------------------------------------------------------------------ */
/* Success / Pending lists                                             */
/* ------------------------------------------------------------------ */
class EventsFragment : Fragment(R.layout.fragment_events) {

    companion object {
        const val MODE_SUCCESS = "success"
        const val MODE_PENDING = "pending"

        fun newInstance(mode: String): EventsFragment {
            val f = EventsFragment()
            f.arguments = Bundle().apply { putString("mode", mode) }
            return f
        }
    }

    private val mode: String get() = arguments?.getString("mode") ?: MODE_SUCCESS

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val btn = v.findViewById<View>(R.id.btnAction)
        if (mode == MODE_PENDING) {
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { sendPending() }
        } else {
            btn.visibility = View.GONE
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (isAdded) {
                load()
                ui.postDelayed(this, 4000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
        SyncRunner.trigger(requireContext())
        ui.postDelayed(tick, 4000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun sendPending() {
        val ctx = requireContext()
        if (!Prefs(ctx).isConfigured) {
            toast("Pair the device first (Device Settings)")
            return
        }
        if (!UiUtil.isOnline(ctx)) {
            toast("Internet is OFF. Turn it on, then tap again.")
            return
        }
        toast("Sending...")
        val app = ctx.applicationContext
        val act = requireActivity()
        Thread {
            Db.get(app).retryFailed()
            val out = SyncEngine.run(app)
            Notifier.update(app)
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                load()
                toast(if (out == SyncEngine.Outcome.RETRY) "Server/network problem. Will retry automatically." else "Done")
            }
        }.start()
    }

    private fun load() {
        val v = view ?: return
        val ctx = requireContext()
        val db = Db.get(ctx)
        val statuses = if (mode == MODE_SUCCESS) St.success else St.waiting
        val items = db.listByStatus(statuses, 200)

        v.findViewById<TextView>(R.id.tvCount).text =
            (if (mode == MODE_SUCCESS) "Used / Success: " else "Pending: ") + items.size

        val list = v.findViewById<LinearLayout>(R.id.list)
        val empty = v.findViewById<TextView>(R.id.tvEmpty)
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (mode == MODE_SUCCESS) "No used payments yet." else "Nothing pending. All good!"

        val inflater = LayoutInflater.from(ctx)
        for (e in items) {
            val row = inflater.inflate(R.layout.item_event, list, false)
            val p = Providers.byKey(e.provider)

            val badge = row.findViewById<TextView>(R.id.tvProvider)
            badge.text = p.label
            badge.backgroundTintList = ColorStateList.valueOf(p.color)

            row.findViewById<TextView>(R.id.tvAmount).text = UiUtil.money(e.amount)
            row.findViewById<TextView>(R.id.tvTrx).text = "TrxID  " + e.trxId

            val st = row.findViewById<TextView>(R.id.tvStatus)
            st.text = UiUtil.statusLabel(e.status)
            st.setTextColor(UiUtil.levelColor(ctx, UiUtil.statusLevel(e.status)))

            row.findViewById<TextView>(R.id.tvTime).text =
                if (e.smsTime.isNotEmpty()) e.smsTime else UiUtil.dateTime(e.createdAt)

            val err = row.findViewById<TextView>(R.id.tvError)
            if (e.error.isNullOrEmpty()) {
                err.visibility = View.GONE
            } else {
                err.visibility = View.VISIBLE
                err.text = e.error
            }
            list.addView(row)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Manual send                                                         */
/* ------------------------------------------------------------------ */
class ManualFragment : Fragment(R.layout.fragment_manual) {

    private fun selectedProvider(v: View): Provider =
        when (v.findViewById<RadioGroup>(R.id.rgProvider).checkedRadioButtonId) {
            R.id.rbNagad -> Providers.NAGAD
            R.id.rbRocket -> Providers.ROCKET
            else -> Providers.BKASH
        }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val etSms = v.findViewById<EditText>(R.id.etSms)

        v.findViewById<View>(R.id.btnPaste).setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
            if (t.isBlank()) toast("Clipboard is empty") else etSms.setText(t)
        }
        v.findViewById<View>(R.id.btnClear).setOnClickListener {
            etSms.setText("")
            v.findViewById<View>(R.id.resultCard).visibility = View.GONE
        }
        v.findViewById<View>(R.id.btnSendManual).setOnClickListener { send(v) }
    }

    private fun showResult(v: View, msg: String, level: Int) {
        val card = v.findViewById<View>(R.id.resultCard)
        val tv = v.findViewById<TextView>(R.id.tvResult)
        card.visibility = View.VISIBLE
        tv.text = msg
        tv.setTextColor(UiUtil.levelColor(requireContext(), level))
    }

    private fun send(v: View) {
        val ctx = requireContext()
        val text = v.findViewById<EditText>(R.id.etSms).text.toString().trim()
        if (text.isEmpty()) {
            showResult(v, "Paste the full SMS first.", 2)
            return
        }
        val provider = selectedProvider(v)
        val parsed = SmsParsers.parse(provider, text)
        if (parsed == null) {
            showResult(
                v,
                "This text does not look like a ${provider.label} \"money received\" SMS. " +
                    "Copy the full original SMS and try again.",
                2
            )
            return
        }

        val btn = v.findViewById<View>(R.id.btnSendManual)
        btn.isEnabled = false
        showResult(v, "Sending...", 1)

        val app = ctx.applicationContext
        val act = requireActivity()
        val online = UiUtil.isOnline(ctx)

        Thread {
            val db = Db.get(app)
            var info: String? = null

            val existing = db.find(parsed.trxId)
            if (existing == null) {
                db.insert(
                    Ev(
                        localId = UUID.randomUUID().toString(),
                        provider = provider.key,
                        trxId = parsed.trxId,
                        amount = parsed.amount,
                        sender = parsed.sender,
                        smsTime = parsed.smsTime,
                        raw = text,
                        status = St.PENDING,
                        error = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else if (existing.status in St.retryable) {
                db.resetToPending(existing.localId, text)
            } else {
                info = "This TrxID (${parsed.trxId}) was already sent: " +
                    UiUtil.statusLabel(existing.status)
            }

            var msg: String
            var level: Int
            if (info != null) {
                msg = info
                level = 0
            } else if (!Prefs(app).isConfigured) {
                msg = "Saved in Pending, but the device is not paired. Open Device Settings."
                level = 2
            } else if (!online) {
                msg = "Internet is OFF. Saved in Pending.\nTurn internet on, then open Pending page and tap \"Send pending now\"."
                level = 1
            } else {
                val out = SyncEngine.run(app)
                val ev = db.find(parsed.trxId)
                when {
                    ev == null -> { msg = "Something went wrong."; level = 2 }
                    ev.status == St.USED -> {
                        msg = "Already used on the website (${ev.trxId})."
                        level = 0
                    }
                    ev.status == St.SYNCED -> {
                        msg = "Sent to server.\n${provider.label}  ${UiUtil.money(ev.amount)}  |  ${ev.trxId}"
                        level = 0
                    }
                    ev.status == St.DUPLICATE -> {
                        msg = "Server already has this TrxID (${ev.trxId})."
                        level = 0
                    }
                    ev.status == St.FLAGGED -> {
                        msg = "Sent, but flagged for admin review."
                        level = 1
                    }
                    ev.status == St.REJECTED -> {
                        msg = "Rejected by server: " + (ev.error ?: "")
                        level = 2
                    }
                    ev.status == St.FAILED -> {
                        msg = "Failed: " + (ev.error ?: "")
                        level = 2
                    }
                    else -> {
                        msg = if (out == SyncEngine.Outcome.RETRY)
                            "Server/network problem. Saved in Pending and will retry."
                        else "Saved in Pending."
                        level = 1
                    }
                }
            }

            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                btn.isEnabled = true
                showResult(v, msg, level)
            }
        }.start()
    }
}

/* ------------------------------------------------------------------ */
/* About                                                               */
/* ------------------------------------------------------------------ */
class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        v.findViewById<TextView>(R.id.tvVersion).text = "Version $ver"
        v.findViewById<View>(R.id.btnWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.website_url))))
            } catch (e: Exception) {
                toast("No browser found")
            }
        }
    }
}
