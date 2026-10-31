package com.smmpaysync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/* ================================================================== */
/* Dashboard: status at a glance                                        */
/* ================================================================== */
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (isAdded) {
                refresh()
                ui.postDelayed(this, 4000)
            }
        }
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        v.findViewById<View>(R.id.cardSuccess).setOnClickListener { act.show(R.id.nav_success) }
        v.findViewById<View>(R.id.cardPending).setOnClickListener { act.show(R.id.nav_pending) }
        v.findViewById<View>(R.id.cardFailed).setOnClickListener { act.show(R.id.nav_pending) }

        v.findViewById<View>(R.id.btnSyncNow).setOnClickListener {
            val ctx = requireContext()
            if (!Prefs(ctx).isConfigured) {
                toast("Set up the server first (Settings)")
                return@setOnClickListener
            }
            toast("Sync started")
            SyncRunner.trigger(ctx)
            v.postDelayed({ if (isAdded) refresh() }, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.postDelayed(tick, 4000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun refresh() {
        val v = view ?: return
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val db = Db.get(ctx)

        val sms = hasSms(ctx)
        val configured = prefs.isConfigured
        val running = prefs.monitor
        val active = sms && configured && running

        v.findViewById<MaterialCardView>(R.id.heroCard).setCardBackgroundColor(
            ContextCompat.getColor(ctx, if (active) R.color.success else R.color.danger)
        )
        v.findViewById<TextView>(R.id.tvHeroState).text = if (active) "Running" else "Not running"
        v.findViewById<TextView>(R.id.tvHeroSub).text = when {
            !sms -> "SMS permission is missing. Open Settings > Permissions."
            !configured -> "Server is not set up. Open Settings > Server connection."
            !running -> "Background service is off. Turn it on in Settings."
            else -> "Payment SMS are captured and sent automatically."
        }

        v.findViewById<TextView>(R.id.tvSuccess).text = db.count(St.success).toString()
        v.findViewById<TextView>(R.id.tvPending).text =
            db.count(listOf(St.PENDING) + St.onServer).toString()
        v.findViewById<TextView>(R.id.tvFailed).text =
            db.count(listOf(St.FAILED, St.REJECTED)).toString()

        val box = v.findViewById<LinearLayout>(R.id.statusBox)
        box.removeAllViews()
        val online = UiUtil.isOnline(ctx)
        box.addView(UiUtil.statusRow(ctx, "Internet", if (online) "Connected" else "Offline", if (online) 0 else 1))

        val res = prefs.lastResult
        box.addView(
            UiUtil.statusRow(
                ctx, "Server", if (res.isEmpty()) "Not tested" else res,
                if (res == "OK") 0 else if (res.isEmpty()) 1 else 2
            )
        )
        val last = prefs.lastSync
        box.addView(
            UiUtil.statusRow(
                ctx, "Last sync",
                if (last == 0L) "Never" else UiUtil.dateTime(last),
                if (last == 0L) 1 else 0
            )
        )
        val seen = prefs.seenAt
        box.addView(
            UiUtil.statusRow(
                ctx, "Last SMS seen",
                if (seen == 0L) "None yet" else UiUtil.dateTime(seen),
                if (seen == 0L) 1 else 0
            )
        )
    }
}

/* ================================================================== */
/* Settings: everything configurable in one place                       */
/* ================================================================== */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (isAdded) {
                refreshInfo()
                ui.postDelayed(this, 4000)
            }
        }
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val prefs = Prefs(ctx)

        val etUrl = v.findViewById<EditText>(R.id.etUrl)
        val etToken = v.findViewById<EditText>(R.id.etToken)
        val etSecret = v.findViewById<EditText>(R.id.etSecret)
        etUrl.setText(prefs.url)
        etToken.setText(prefs.token)
        etSecret.setText(prefs.secret)

        v.findViewById<View>(R.id.btnSave).setOnClickListener {
            val url = etUrl.text.toString().trim()
            val token = etToken.text.toString().trim()
            val secret = etSecret.text.toString().trim()
            if (!url.startsWith("https://")) {
                toast("URL must start with https://")
                return@setOnClickListener
            }
            if (token.isEmpty() || secret.isEmpty()) {
                toast("Token and Secret are required")
                return@setOnClickListener
            }
            prefs.url = url
            prefs.token = token
            prefs.secret = secret
            prefs.lastResult = ""
            // Retry events that previously failed because setup or network was wrong.
            Db.get(ctx).retryFailed()
            toast("Saved")
            MonitorService.ensure(ctx)
            SyncWorker.enqueue(ctx)
            SyncRunner.trigger(ctx)
        }

        v.findViewById<View>(R.id.btnTestConn).setOnClickListener {
            toast("Testing connection...")
            val app = ctx.applicationContext
            val act = requireActivity()
            Thread {
                val msg = SyncEngine.ping(app)
                act.runOnUiThread {
                    toast(msg)
                    if (isAdded) refreshInfo()
                }
            }.start()
        }

        v.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            prefs.clear()
            etUrl.setText("")
            etToken.setText("")
            etSecret.setText("")
            toast("Server disconnected")
            refreshInfo()
        }

        val switches = mapOf(
            Providers.BKASH to v.findViewById<SwitchMaterial>(R.id.swBkash),
            Providers.NAGAD to v.findViewById<SwitchMaterial>(R.id.swNagad),
            Providers.ROCKET to v.findViewById<SwitchMaterial>(R.id.swRocket)
        )
        for ((p, sw) in switches) {
            sw.isChecked = prefs.providerEnabled(p)
            sw.setOnCheckedChangeListener { _, checked -> prefs.setProviderEnabled(p, checked) }
        }

        val swMonitor = v.findViewById<SwitchMaterial>(R.id.swMonitor)
        swMonitor.isChecked = prefs.monitor
        swMonitor.setOnCheckedChangeListener { _, checked ->
            prefs.monitor = checked
            if (checked) MonitorService.ensure(ctx) else MonitorService.stop(ctx)
            refreshInfo()
        }

        v.findViewById<View>(R.id.btnPerm).setOnClickListener {
            val need = ArrayList<String>()
            if (!hasSms(ctx)) {
                need.add(Manifest.permission.RECEIVE_SMS)
                need.add(Manifest.permission.READ_SMS)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isEmpty()) toast("All permissions already granted")
            else ActivityCompat.requestPermissions(requireActivity(), need.toTypedArray(), 1)
        }

        v.findViewById<View>(R.id.btnBattery).setOnClickListener {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + ctx.packageName)
                startActivity(i)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    toast("Open phone Settings > Battery manually")
                }
            }
        }

        v.findViewById<View>(R.id.btnAppInfo).setOnClickListener {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + ctx.packageName)
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        ui.postDelayed(tick, 4000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun refreshInfo() {
        val v = view ?: return
        val ctx = requireContext()
        val prefs = Prefs(ctx)

        val perm = v.findViewById<LinearLayout>(R.id.permBox)
        perm.removeAllViews()
        val sms = hasSms(ctx)
        perm.addView(UiUtil.statusRow(ctx, "SMS (receive + read)", if (sms) "Granted" else "Missing", if (sms) 0 else 2))
        val notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        perm.addView(UiUtil.statusRow(ctx, "Notifications", if (notif) "Allowed" else "Blocked", if (notif) 0 else 1))
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        perm.addView(UiUtil.statusRow(ctx, "Battery", if (unrestricted) "Unrestricted" else "Restricted", if (unrestricted) 0 else 1))
        perm.addView(UiUtil.statusRow(ctx, "Background service", if (prefs.monitor) "On" else "Off", if (prefs.monitor) 0 else 2))

        val diag = v.findViewById<LinearLayout>(R.id.diagBox)
        diag.removeAllViews()
        val seen = prefs.seenAt
        diag.addView(
            UiUtil.statusRow(
                ctx, "Last SMS seen",
                if (seen == 0L) "None yet" else UiUtil.dateTime(seen) + "\n" + prefs.seenInfo,
                if (seen == 0L) 1 else 0
            )
        )
        val broadcastAt = prefs.lastSmsBroadcastAt
        diag.addView(
            UiUtil.statusRow(
                ctx, "Last Android SMS broadcast",
                if (broadcastAt == 0L) "Not received yet" else
                    UiUtil.dateTime(broadcastAt) + "\nSender ID: " + prefs.lastSmsBroadcastSender,
                if (broadcastAt == 0L) 1 else 0
            )
        )
        val res = prefs.lastResult
        diag.addView(
            UiUtil.statusRow(
                ctx, "Server", if (res.isEmpty()) "Not tested" else res,
                if (res == "OK") 0 else if (res.isEmpty()) 1 else 2
            )
        )
        val last = prefs.lastSync
        diag.addView(
            UiUtil.statusRow(
                ctx, "Last successful sync",
                if (last == 0L) "Never" else UiUtil.dateTime(last),
                if (last == 0L) 1 else 0
            )
        )
    }
}
