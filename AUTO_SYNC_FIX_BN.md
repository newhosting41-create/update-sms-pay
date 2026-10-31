# v4 Auto-Sync fix — কী সমস্যা ছিল, কী বদলেছে

## v4-তে app না খুললে কেন আটকে যাচ্ছিল

1. `MainActivity.onResume()` থেকে `SyncRunner.trigger()` চলে। `SyncRunner.runOnce()` প্রথমে `InboxScanner.scan()` করে ফোনের inbox-এর নতুন SMS local database-এ আনে। কিন্তু v4-এর `SyncWorker` শুধু database-এর pending row পাঠাত; background worker নিজে inbox scan করত না। Live SMS receiver কোনো কারণে message ধরতে না পারলে app খুললে scan-এ SMS দেখা যেত—এতে মনে হতো app খুললেই শুধু server-এ যাচ্ছে।
2. v4-এর SMS broadcast path-এ `SmsIngest.handle(..., sendInline=true)` আগে `SyncRunner.runOnce()` চালাত। ওই ফাংশনে inbox scan-এর পাশাপাশি 15s connect timeout ও 20s read timeout-সহ network call হতে পারত; কিন্তু persistent WorkManager job enqueue করা হতো ওই synchronous কাজ শেষ হওয়ার পরে। SMS receiver-এর কাজের সময় সীমিত—OS process থামালে job enqueue-ই নাও হতে পারত।
3. v4-এর `SyncEngine` server payload-এ `action` দেয়নি। আগের PHP endpoint `action="event"` চায়; না থাকলে HTTP 400 `Unsupported action` দেয়। ফলে background retry হলেও event গ্রহণ হতো না।

## কী সংশোধন করা হয়েছে

- Valid SMS database-এ save হওয়ার সঙ্গে সঙ্গে network-constrained WorkManager request enqueue হয়; receiver HTTP response-এর জন্য অপেক্ষা করে না।
- Internet না থাকলে WorkManager request network constraint-এর জন্য অপেক্ষা করবে; internet ফিরলে OS কাজটি চালানোর চেষ্টা করবে।
- Background `SyncWorker` প্রথমে `InboxScanner.scan()` চালায় (READ_SMS permission থাকলে), ফলে live receiver miss করা নতুন SMS-ও inbox থেকে তুলে তারপর server-এ পাঠাবে। Worker-কে nested duplicate jobs enqueue না করতে scanner/ingest-এর scheduling flag যোগ করা হয়েছে।
- Event request-এ server-required `action: "event"` যোগ হয়েছে।
- credentials ঠিক করার পর Settings-এ Save চাপলে retry করার উপযোগী FAILED/REJECTED event-ও pending-এ ফেরে এবং background job enqueue হয়। `UNREAD-*` parser-error entry-গুলো স্বয়ংক্রিয়ভাবে server-এ পাঠানো হয় না।

## GitHub-এ নতুন build বসানো

1. Project extract করে সংশোধিত source আপনার private GitHub repository-তে commit/push করুন।
2. GitHub repository-তে **Actions → Build APK** workflow সফল হওয়া পর্যন্ত অপেক্ষা করুন।
3. ওই run-এর Artifacts থেকে `SmmPaySync-debug-apk` download করে APK install করুন।
4. পুরোনো app-এর একই package/signature থাকলে Android Studio-তে একই PC-এর debug keystore দিয়ে build করলে app data সাধারণত থাকে। Signature mismatch হলে **পুরোনো app uninstall করবেন না**—uninstall করলে offline queue/credentials মুছে যাবে। একই signing key ব্যবহার করে build করতে হবে।
5. প্রথমবার app খুলে SMS + READ_SMS permission দিন, Settings-এ correct HTTPS URL/token/secret রাখুন, bKash switch on রাখুন, Background service switch on রাখুন, এবং battery restriction কমান। Setup-এর পরে app বন্ধ করে test করুন।

## কীভাবে বোঝবেন কোন ধাপে আটকে আছে

- **Settings → Diagnostics → Last SMS seen** নতুন SMS-এর সময় না বদলালে receiver permission, sender ID/header, provider switch, Android restricted-settings বা OEM battery control পরীক্ষা করুন। Worker-এর inbox scanner-এর জন্য READ_SMS permission-ও দরকার।
- **Last SMS seen বদলায় এবং Pending count বাড়ে, কিন্তু server-এ যায় না**—Settings-এ URL/token/secret ও internet দেখুন। এই fix-এর আগে PHP endpoint-এ `Unsupported action` 400 আসতে পারত; event action যোগ হয়েছে।
- Monitor foreground notification দেখা গেলে persistent service চলছে; notification না থাকলে app Settings-এ Background service switch এবং Android App info/autostart পরীক্ষা করুন। Android-এর Force stop দিলে app আবার নিজে চলা শুরু করতে পারে না—app হাতে খুলতে হবে।

## সময় সম্পর্কে বাস্তব প্রত্যাশা

SMS receiver event পেলে one-time WorkManager কাজ network পাওয়া মাত্র চালানোর চেষ্টা করবে। Receiver সম্পূর্ণ miss হলে periodic worker inbox scan করবে—এর interval 15 মিনিট, এবং Android Doze/OEM battery policy আরও দেরি করাতে পারে। Foreground service চলমান থাকলে v4-এর network callback সংযোগ ফেরার পর দ্রুত retry করে। Android কোনো app-কে সব ফোনে একদম তাৎক্ষণিক background execution নিশ্চিত করে না; তবে app না খুলে eventual auto-send-এর জন্য প্রয়োজনীয় code path এই patch-এ আছে।

**Nagad/Rocket:** PHP endpoint-টি bKash-only হলে তাদের event ওই endpoint-এ গ্রহণ হবে না। Nagad/Rocket auto-credit/auto-verify দাবি করবেন না—provider-specific backend route/parser আলাদা করে তৈরি ও test করতে হবে। SMS text/HMAC নিজে bank-verified proof নয়।
