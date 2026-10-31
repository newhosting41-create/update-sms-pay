# Screen off-এ SMS receive করে সঙ্গে সঙ্গে server-এ পাঠানো — v6

এই update-এ reference APK-এর কার্যকর flow—`SMS_RECEIVED` → sender detect → local save → সঙ্গে সঙ্গে server POST—আপনার SmmPaySync-এ যোগ করা হয়েছে। তবে reference APK-এর আলাদা `subpay.paykureghor.com` URL বা form-urlencoded credentials কপি করা হয়নি। আপনার app আগের মতোই HTTPS URL, Device Token, HMAC Secret এবং signed JSON endpoint ব্যবহার করে।

## SMS এলে এখন কী হবে

1. Android `SmsReceiver` screen/app খোলা না থাকলেও `SMS_RECEIVED` broadcast পেলে sender ও message নেয়। Runtime SMS permissions এবং accepted sender header দরকার।
2. Supported provider switch ON থাকলে SMS parse করে SQLite queue-তে save হয়। একই TrxID আগেই থাকলে duplicate row বানায় না।
3. App একই receiver কাজের ভেতর ঐ TrxID-এর জন্য একটি bounded immediate HTTPS POST চেষ্টা করে—connect timeout 2.5 sec, read timeout 3.5 sec; `action=event`, provider, raw SMS এবং local event ID JSON-এ যায়; token/HMAC headers আগের মতো থাকে। এটি reference APK-এর direct-send আচরণের নিকটবর্তী, কিন্তু আপনার server API contract অক্ষত রাখে।
4. HTTP 200-এর JSON-এ `success=true` না থাকলে app success দেখায় না। Server-returned duplicate/flagged/rejected/error status আলাদা করে local history-তে থাকে।
5. Immediate attempt ব্যর্থ হলে, busy থাকলে, credentials না থাকলে বা network না থাকলে event local Pending-এ থাকে। SMS receiver 15-second delay-সহ persisted, network-constrained WorkManager fallback আগে enqueue করে; internet এলে worker retry করবে। Periodic/inbox recovery আগের মতোই backup হিসেবে থাকে।

## সীমা ও প্রয়োজনীয় setup

- Screen off থাকলেও SMS broadcast আসতে পারে; internet না থাকলে server-এ তখনই পাঠানো সম্ভব নয়—SMS local queue-তে থাকবে এবং connection ফিরলে retry হবে। Android/OEM background policy, Doze, battery controls বা Force stop execution delay/stop করতে পারে; তাই নির্দিষ্ট সেকেন্ডে delivery নিশ্চয়তা নয়।
- App একবার খুলে `RECEIVE_SMS` permission দিন; inbox recovery চাইলে `READ_SMS`-ও দিন। Device Settings-এ HTTPS event endpoint, Device Token, HMAC Secret save করুন এবং সঠিক provider switch ON রাখুন। Setup-এর পরে প্রতিটি SMS-এর জন্য app খুলতে হবে না। Android-এর Force stop এড়িয়ে চলুন; force-stop থাকলে app হাতে খুলতে হয়।
- Backend endpoint-কে SmmPaySync signed JSON contract (`action=event`, `sender`, `provider`, `raw_sms`, `local_event_id`; `X-Device-Token`, `X-Signature`) গ্রহণ করতে হবে এবং idempotent হতে হবে। আগের PHP route bKash-only; Nagad/Rocket server sender allowlist ও SMS parser আলাদা করে extend না করলে ওই provider-এর request 422/rejected হবে।
- SMS body-কে ব্যাংকের cryptographic proof ভাববেন না। Server SMS-কে unverified event হিসেবে রাখবে; শুধু SMS পাওয়ার ভিত্তিতে wallet auto-credit করা উচিত নয়।

## নতুন source থেকে APK build

1. ZIP extract করুন এবং Android Studio-তে project root—যে folder-এ `settings.gradle.kts` আছে—**File → Open** দিয়ে খুলুন।
2. Gradle Sync শেষ করুন। `app/build.gradle.kts`-এ version `1.1.0`, code `2` হয়েছে।
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)** দিন। APK সাধারণত `app/build/outputs/apk/debug/app-debug.apk`-তে তৈরি হবে।
4. আগের app update করতে হলে একই application ID এবং একই signing key দরকার। Signature mismatch এলে পুরোনো app uninstall করবেন না—offline SMS queue ও saved credentials হারাতে পারেন। একই signing key দিয়ে build করুন, নইলে আগে data/queue সংরক্ষণের পরিকল্পনা করুন।
5. ফোনে নতুন APK install করে Settings-এ endpoint ও credentials যাচাই করুন। Screen off করে বাস্তব payment SMS দিয়ে পরীক্ষা করুন। Server access/error log-এ `/api/add-data` নয়—আপনার configured SmmPaySync endpoint-এর POST দেখা উচিত।

## যদি আবার server-এ না যায়

- `Settings → Diagnostics → Last Android SMS broadcast`: SMS-এর সময় update না হলে permission, sender header, app Force stop ও ফোনের Autostart/battery settings যাচাই করুন।
- Broadcast time update হয়, কিন্তু provider ignored/OFF: প্রকৃত sender address এবং provider switch যাচাই করুন; conversation title সবসময় প্রকৃত SMS sender ID নয়।
- Pending row আছে কিন্তু request নেই: configured HTTPS URL, Internet, WorkManager/background restrictions দেখুন।
- Server 401/403: token/HMAC secret মেলেনি। 422: backend parser/sender/provider contract mismatch; বিশেষ করে Nagad/Rocket route আলাদা করে support করতে হবে।
- HTTP 200 পেলেও `success` JSON না থাকলে app event-কে accepted দেখাবে না—backend response body ও logs পরীক্ষা করুন।
