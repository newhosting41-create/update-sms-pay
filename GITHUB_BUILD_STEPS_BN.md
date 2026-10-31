# GitHub থেকে SmmPaySync v6 APK build — সহজ ধাপ

এই source version `1.1.0` / `versionCode 2`; এতে SMS এলে immediate-send attempt এবং WorkManager fallback আছে। ZIP extract করে project-এর ভেতরের files repository root-এ upload/push করবেন—ZIP নিজে repository-তে রাখবেন না। `.github/workflows/build.yml` অবশ্যই repository root-এ থাকতে হবে।

## GitHub Desktop দিয়ে repository তৈরি

1. ZIP extract করুন। যে folder-এর মধ্যে `settings.gradle.kts`, `app/`, `build.gradle.kts` এবং `.github/workflows/build.yml` আছে সেটিই project root।
2. GitHub Desktop-এ sign in করে **File → Add Local Repository…** চাপুন, project root নির্বাচন করুন। Git repository না হলে **Create a repository** option দিয়ে তৈরি করুন।
3. Repository name `SmmPaySync` দিন এবং **Keep this code private** নির্বাচন করুন। Financial SMS-সম্পর্কিত source public করবেন না।
4. Changes-এ project files ও `.github/workflows/build.yml` দেখুন। Commit message লিখে **Commit to main**, তারপর **Publish repository** চাপুন।
5. Device Token বা HMAC Secret code/README/GitHub-এ লিখবেন না; শুধু app-এর Settings-এ দিন।

## Actions থেকে APK download

1. GitHub repository খুলে **Actions** tab-এ যান। প্রথমবার হলে workflows enable করুন।
2. **Build APK** workflow-এর successful run খুলুন। Push করলে workflow নিজে run হয়; না হলে **Run workflow** চাপুন।
3. সবুজ check হলে **Artifacts → SmmPaySync-debug-apk** download করুন এবং ZIP extract করুন। APK Android phone-এ install করুন।

## নতুন version ফোনে install ও পরীক্ষা

1. Android Studio/GitHub Actions build-এ একই `applicationId` (`com.smmpaysync.app`) থাকবে, তবে signature একই না হলে Android update আটকাতে পারে। Pending SMS/credentials থাকলে পুরোনো app uninstall করবেন না; আগে একই signing key দিয়ে build নিশ্চিত করুন বা local data হারানোর ঝুঁকি বুঝুন।
2. Install-এর পর app প্রথমবার খুলে `RECEIVE_SMS` permission দিন; inbox recovery চাইলে `READ_SMS`-ও দিন। Settings-এ সঠিক HTTPS URL, Device Token, HMAC Secret দিন এবং provider switch চালু রাখুন।
3. এরপর test SMS আসার সময় app বন্ধ ও screen off রাখুন, internet on রাখুন। App-এর Settings/Diagnostics-এ broadcast time ও status পরীক্ষা করুন এবং আপনার server access/error log-এ request এসেছে কি না দেখুন। প্রতিটি SMS-এর জন্য app খোলা লাগার কথা নয়; Force stop করলে Android background delivery থামাতে পারে।

## GitHub build error হলে

Actions-এর failed run খুলে **Build debug APK** step-এর প্রথম error lines দেখুন। Screenshot/লগ পাঠানোর আগে Token, Secret, SMS message ও ব্যক্তিগত নম্বর ঢেকে দিন।
