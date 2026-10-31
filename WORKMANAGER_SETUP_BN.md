# Screen-off SMS auto-sync: WorkManager setup (Kotlin)

এই guide `com.smmpaysync.app` v4 project-এর জন্য। এখানে **SMS এলে local database-এ save → সঙ্গে সঙ্গে network-required WorkManager job enqueue → internet থাকলে server-এ send** করা হয়। WorkManager request app process বন্ধ হলেও persist করে। Android Doze, user-এর Force stop, permission revoke, ফোনের OEM battery manager, বা network না থাকলে execution দেরি হতে পারে; কোনো app-ই সব অবস্থায় instant delivery guarantee করতে পারে না।

## আগে গুরুত্বপূর্ণ সিদ্ধান্ত: এই কাজের জন্য Foreground Service লাগবে না

SMS receive করার receiver-কে খুব দ্রুত কাজ শেষ করতে হয়। Android-এর guidance অনুযায়ী receiver থেকে long-running thread চালিয়ে রাখা নিরাপদ নয়; দীর্ঘ কাজের জন্য job schedule করতে হবে। SMS receiver ছোট কাজ করবে, তারপর WorkManager schedule করবে।

এই project-এর `MonitorService` ৩০ সেকেন্ড অন্তর কাজ চালানোর চেষ্টা করে; এটিকে guarantee মনে করবেন না। Android 14+ এ `specialUse` foreground service শুধু যথার্থ special-use কাজের জন্য এবং Play Console-এ declaration/review-এর অধীন। Server-এ ছোট JSON upload-এর জন্য persistent FGS-কে workaround হিসেবে ব্যবহার না করে WorkManager ব্যবহার করুন। `specialUse` type বৈধ না হলে service start ব্যর্থও হতে পারে।

## 1. Gradle dependency

`app/build.gradle.kts`-এর `dependencies` block-এ এটি থাকতে হবে (আপনার v4-তে ইতিমধ্যে আছে):

```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.1")
```

Gradle Sync করুন।

## 2. Permissions ও receiver — `app/src/main/AndroidManifest.xml`

`<manifest>`-এর ভেতরে permission-গুলো রাখুন. `READ_SMS` inbox recovery-তে മാത്രം লাগে; live `SMS_RECEIVED` receiver-এর জন্য `RECEIVE_SMS` দরকার। `INTERNET` server upload-এর জন্য আবশ্যক।

```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

`<application>`-এর ভেতরে SMS receiver declaration:

```xml
<receiver
    android:name=".SmsReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>
```

`android:permission="android.permission.BROADCAST_SMS"` দিয়ে receiver-কে system SMS broadcast-এ সীমিত করা হয়। `RECEIVE_SMS` runtime permission-ও ফোনের Settings-এ granted হতে হবে। User-এর স্পষ্ট সম্মতি ছাড়া SMS পড়বেন বা server-এ পাঠাবেন না।

## 3. WorkManager scheduler — নতুন `PaymentSyncScheduler.kt`

Path: `app/src/main/java/com/smmpaysync/app/PaymentSyncScheduler.kt`

```kotlin
package com.smmpaysync.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PaymentSyncScheduler {
    private const val EVENT_WORK = "payment_sms_upload"
    private const val PERIODIC_WORK = "payment_sms_recovery"

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /** Call after a new SMS has been committed to the local database. */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PaymentSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(EVENT_WORK)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                EVENT_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
    }

    /** Safety net for SMS missed by the broadcast receiver. Minimum period is 15 minutes. */
    fun scheduleInboxRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<PaymentSyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraints())
            .addTag(PERIODIC_WORK)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
```

`APPEND_OR_REPLACE`-এর ফলে দ্রুত পরপর আসা SMS-এর upload কাজগুলো queue হয়; worker database-এর pending event-গুলো পাঠায়। Network না থাকলে `CONNECTED` constraint-এর কারণে job অপেক্ষা করবে, আর সাময়িক ব্যর্থতায় backoff দিয়ে retry হবে। Periodic scan ১৫ মিনিটের আগে চলার প্রতিশ্রুতি দেয় না; বাস্তবে এর চেয়েও দেরি হতে পারে।

## 4. Worker — `PaymentSyncWorker.kt`

Path: `app/src/main/java/com/smmpaysync/app/PaymentSyncWorker.kt`

এই code project-এর `InboxScanner`, `SyncEngine`, `Db`, `Notifier` class ব্যবহার করে। `SyncEngine.run()`-এর request body-তে server যে action চায় সেটি থাকা জরুরি—আপনার PHP event endpoint-এর জন্য `action` অবশ্যই `event` হতে হবে.

```kotlin
package com.smmpaysync.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PaymentSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        return try {
            // READ_SMS permission থাকলে broadcast miss হওয়া নতুন SMS-ও local queue-তে নেয়.
            InboxScanner.scan(app, scheduleWorker = false)

            when (SyncEngine.run(app)) {
                SyncEngine.Outcome.RETRY -> Result.retry()

                SyncEngine.Outcome.NOT_CONFIGURED -> {
                    // Event local DB-তে থাকবে. Settings Save করার পর নতুন job enqueue হবে.
                    Notifier.update(app)
                    Result.success()
                }

                SyncEngine.Outcome.DONE -> {
                    // Do not call pollStatus unless this exact endpoint supports action=status.
                    Notifier.update(app)

                    // SyncEngine currently sends a bounded batch. Queue another pass if needed.
                    if (Db.get(app).pending().isNotEmpty()) {
                        PaymentSyncScheduler.enqueueNow(app)
                    }
                    Result.success()
                }
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
```

### `SyncEngine.kt`-এর request body-তে এই field নিশ্চিত করুন

`JSONObject()` তৈরি করার জায়গায়:

```kotlin
val body = JSONObject()
    .put("action", "event")
    .put("sender", provider.serverSender)
    .put("provider", provider.key)
    .put("raw_sms", e.raw)
    .put("local_event_id", e.localId)
    .toString()
```

এখানে HMAC signature-টি **এই JSON-এর ঠিক যে bytes server-এ POST হয়, সেই একই bytes** থেকে হিসাব করতে হবে। URL-এ redirect থাকলে final HTTPS endpoint সরাসরি দিন; redirect follow না করলে 301/302 upload ব্যর্থ করতে পারে।

## 5. SMS receiver — দ্রুত save ও enqueue

`SmsReceiver.kt`-এ receiver-এর কাজ শুধু sender/message বের করে database-এ save করা এবং WorkManager enqueue করা। SMS broadcast চলার সময় HTTP upload করবেন না। v4-র `SmsIngest.handle(... fromSmsReceiver = true)` ইতিমধ্যে database save-এর পর `PaymentSyncScheduler.enqueueNow()` কল করবে—সেই call ব্যবহার করুন.

Core pattern:

```kotlin
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val app = context.applicationContext
        val sender = messages.first().originatingAddress.orEmpty()
        val provider = Providers.byHeader(sender) ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val pending = goAsync()
        Thread {
            try {
                // Must save to Room/SQLite and enqueue quickly. No HTTP in this receiver.
                SmsIngest.handle(
                    app,
                    provider,
                    body,
                    fromSmsReceiver = true,
                    scheduleWorker = true
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}
```

`goAsync()` receiver-এর execution window বাড়িয়ে দীর্ঘ কাজ করার অনুমতি দেয় না; database save + WorkManager enqueue কয়েক সেকেন্ডের মধ্যে শেষ করুন।

`SmsIngest.handle()`-এ valid event database-এ insert সফল হলেই:

```kotlin
if (saved) {
    PaymentSyncScheduler.enqueueNow(ctx)
}
```

Duplicate TrxID হলে নতুন event না বানানো ঠিক। কিন্তু আগের row `FAILED`/`REJECTED` হলে Settings Save বা Retry action থেকে সেটিকে pending করে আবার `enqueueNow()` করতে হবে।

## 6. Periodic inbox recovery চালু করুন

`MainActivity.onCreate()`-এ একবার schedule করুন। WorkManager নিজে job-এর schedule persist করে; boot-এর সময় custom service শুরু করানো প্রয়োজন নেই.

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    PaymentSyncScheduler.scheduleInboxRecovery(this)
    // বাকি UI setup...
}
```

Server URL/token/secret Save করার পর local pending event retry এবং নতুন job schedule করুন:

```kotlin
Db.get(context).retryFailed()
PaymentSyncScheduler.enqueueNow(context)
```

## 7. Foreground Service কী করবেন?

এই upload workflow-তে `MonitorService`-এর ৩০ সেকেন্ড polling জরুরি নয়। WorkManager-কে primary path করুন। `MonitorService.ensure()` ব্যর্থ হলেও SMS receiver-এর one-time WorkManager job স্বাধীনভাবে enqueue হতে হবে। Foreground service ব্যবহার করতে চাইলে সেটি আলাদা user-visible feature, স্থায়ী notification, সঠিক FGS type/permission, Android version handling, এবং Play Console-এ গ্রহণযোগ্য special-use explanation চাইতে পারে। `specialUse` বসালেই Android বা Google Play background execution নিশ্চিত/অনুমোদিত করে না।

## 8. Android Studio-তে বসানোর ধাপ

1. পুরো project backup করে Android Studio-তে project root খুলুন (যে folder-এ `settings.gradle.kts` আছে)।
2. `app/build.gradle.kts`-এ WorkManager dependency যাচাই করুন; **Sync Now** চাপুন।
3. `PaymentSyncScheduler.kt` ও `PaymentSyncWorker.kt` নতুন Kotlin file হিসেবে package `com.smmpaysync.app`-এ তৈরি করুন।
4. `AndroidManifest.xml`-এ permissions এবং `SmsReceiver` declaration যাচাই করুন; একই receiver দুইবার declare করবেন না।
5. `SyncEngine.kt`-এ `action=event` যাচাই করুন। `SmsIngest`-এ database insert সফল হলে `PaymentSyncScheduler.enqueueNow()` আছে কি না দেখুন।
6. `MainActivity.onCreate()`-এ `scheduleInboxRecovery()` call রাখুন। Settings-এ Save হলে pending retry করে নতুন one-time request দিন।
7. **Build → Make Project** করুন। Build সফল হলে নতুন APK install করুন। শুধু source ZIP extract করলেই ফোনের app update হয় না।
8. ফোনে app খুলে `RECEIVE_SMS`, `READ_SMS` (inbox recovery চাইলে), notification permission দিন। URL direct HTTPS endpoint হোক; Token/Secret server-এর সঙ্গে হুবহু মিলতে হবে। Test connection করুন।
9. App বন্ধ করে screen off রেখে একটি আসল SMS পরীক্ষা করুন। সঙ্গে সঙ্গে না গেলে পরে Pending page-এ দেখুন। Android Studio-এর **App Inspection → Background Task Inspector** বা Logcat-এ `WorkManager`, `WM-WorkerWrapper`, `PaymentSyncWorker` filter করে job `ENQUEUED/RUNNING/FAILED` অবস্থা দেখুন।
10. Test-এর সময় app-কে **Force stop** করবেন না। Force stop হলে Android ব্যবহারকারী app আবার না খোলা পর্যন্ত background কাজ থামিয়ে রাখে। Battery saver/OEM autostart restriction-ও test ফল বদলাতে পারে।

## 9. bKash/Nagad-এর গুরুত্বপূর্ণ backend সীমা

এই guide কেবল Android-এ queue/background upload path ঠিক করে। আপনার সঙ্গে থাকা PHP `bkash_sms_sync.php` endpoint-টি bKash sender-ই গ্রহণ করে। Nagad-এর জন্য provider-specific server endpoint/parser, HMAC/device config, event deduplication এবং response status আলাদা করে যোগ না করলে Nagad SMS app থেকে পাঠালেও server reject করবে। Frontend-এ Nagad switch থাকলেই server-side support তৈরি হয় না। SMS-এর text নিজে provider-এর verified proof নয়; server-এ SMS থেকে সরাসরি wallet credit করবেন না—idempotency, duplicate checks এবং official provider verification/admin review রাখুন।

## 10. কেন শতভাগ instant promise করা যায় না

WorkManager constraints পূরণ হলে কাজ চালায় এবং process death/reboot জুড়ে request সংরক্ষণ করে; কিন্তু এটি exact-time scheduler নয়। Periodic work-এর minimum interval ১৫ মিনিট এবং exact run time system optimization/constraints-এর ওপর নির্ভর করে। Screen অনেকক্ষণ বন্ধ ও ফোন stationary থাকলে Doze network access ও jobs defer করতে পারে। `Force stop`-এর পর user app আবার না খোলা পর্যন্ত auto-run আশা করবেন না। তাই সঠিক প্রতিশ্রুতি হলো **SMS event হলে দ্রুত queue; internet/network ও Android policy অনুমতি দিলে background-এ send; অন্যথায় pending রেখে retry**—“যেকোনো অবস্থায় তাৎক্ষণিকভাবে যাবেই” নয়।

## Android/Google-এর সূত্র

- [Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts): manifest receiver process lifecycle, `goAsync()`-এর সীমা এবং long work schedule করার পরামর্শ।
- [Define WorkRequests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): network constraints, retry/backoff, periodic timing এবং expedited-work quota।
- [Foreground service types for Android 14](https://developer.android.com/about/versions/14/changes/fgs-types-required): type-specific permissions, special-use declaration এবং Play review।
- [Background foreground-service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): background থেকে FGS শুরু করার সীমাবদ্ধতা।
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby): screen-off Doze-এ network/job defer হওয়া।
- [Google Play SMS permissions policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en): SMS permissions high-risk; Play distribution-এর আগে eligible use case, disclosure/consent ও review দরকার হতে পারে।


## 11. v4-এর অতিরিক্ত সংশোধন: unsupported status request বন্ধ করুন

আপনার আগের PHP endpoint `action=ping` এবং `action=event` নেয়; `action=status` route নেই। তাই v4-এর `SyncRunner.runOnce()`-এ event send-এর পরে থাকা `SyncEngine.pollStatus(app)` call-টি ওই endpoint-এর সঙ্গে ব্যবহার করবেন না। `SyncEngine.run()` দিয়ে upload রাখুন; status polling কেবল backend-এ সত্যিকারের `action=status` implementation থাকলে চালু করুন। না হলে SMS server-এ পৌঁছালেও dashboard-এর `lastResult` status check-এর HTTP 400 দেখিয়ে বিভ্রান্ত করতে পারে।

