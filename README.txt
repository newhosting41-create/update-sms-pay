SMM Payment Sync — smartrentalbd.com

This v6 source includes SMS_RECEIVED → local SQLite queue → bounded immediate HTTPS/HMAC upload, with a delayed network-constrained WorkManager fallback. See SCREEN_OFF_AUTO_SYNC_BN.md for Bengali setup, limitations, and troubleshooting.

Package: com.smmpaysync.app
Version: 1.1.0 (versionCode 2)

LOGO: app/src/main/res/drawable/app_logo.xml is a placeholder.
To use your logo, replace it with app/src/main/res/drawable/app_logo.png (square, around 512x512, transparent background) and update the resource reference if needed.

Build: open the project root containing settings.gradle.kts in Android Studio, allow Gradle Sync, then choose Build > Build Bundle(s) / APK(s) > Build APK(s).
