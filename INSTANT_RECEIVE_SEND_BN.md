# Instant SMS receive/send update

আপনার দেওয়া `base.apk`-এর receive/send design দেখে v6-এ মূল flow অনুসরণ করা হয়েছে: manifest SMS receiver → provider/header filter → local record → direct server POST. তবে ওই APK-এর unrelated server URL, device-key fields বা HTTP success shortcut কপি করা হয়নি; আপনার SmmPaySync-এর HMAC-signed JSON ও event validation বহাল।

**তৎক্ষণাৎ পথ:** SMS broadcast এলে receiver queue-তে save করে; internet ও server settings থাকলে শুধু নতুন TrxID-এর জন্য একটি bounded 6-second-connect/read budget-এর HTTPS upload attempt করে। 200 response-এর JSON-এ `success=true` হতে হবে, তারপরই local row server-sent status পায়।

**Fallback:** Direct request ব্যর্থ/ব্যস্ত/offline হলে local Pending row অক্ষত থাকে। Direct send-এর আগে 15-second delay-সহ network-constrained WorkManager backup persist করা হয়; network ফিরলে worker retry করে। Existing periodic worker, inbox recovery, screen-off notification/service code রাখা হয়েছে, কিন্তু upload service start হওয়া আবশ্যক নয়।

**কেন direct thread-ই শুধু যথেষ্ট নয়:** reference APK-তে SMS broadcast থেকে raw Java Thread-এ HTTP POST হয়, কিন্তু `goAsync()` বা job fallback নেই; Android process বন্ধ করলে thread কেটে যেতে পারে। এই project-এ receiver `goAsync()` ব্যবহার করে সীমিত একবার চেষ্টা করে এবং durable WorkManager backup রাখে—তাই copy করা হয়েছে behavior, not the fragile implementation details.

**Backend:** app আগের `X-Device-Token` + `X-Signature` HMAC JSON contract-এই পাঠায়। আগের PHP endpoint bKash-only; Nagad/Rocket-এর জন্য backend-এর sender allowlist, parser ও provider handling যোগ করতে হবে। SMS upload মানেই bank-side verification বা wallet credit নয়।
