# Google Play publishing checklist: OneBattery by Flygon LC

Package `com.flygonlc.onebattery` · developer name **Flygon LC** · researched **2026-11-11** against current Play policy pages (linked at the bottom). Play policy changes often, so re-check anything marked *verify* when you submit.

Legend: 🔴 blocks publishing · 🟠 likely rejection or major review risk · 🟡 required Console paperwork · 🟢 nice to have

---

## 1. Blockers in the code and build

### ✅ 1.1 Target API level must be 36 (Android 16)
**Done:** `compileSdk` and `targetSdk` are 36.

Since **31 Aug 2026**, new apps and app updates must target **API 36**. The app currently has `compileSdk = 35` and `targetSdk = 35`, so Play Console will refuse the upload.

Upgrade (no AGP or Gradle bump strictly required):
- `app/build.gradle.kts`: `compileSdk = 36`, `targetSdk = 36`.
- AGP **8.9.1** (current) is the documented minimum for API 36, with Gradle **8.11.1** (current). Only `compileSdk 36.1` would need AGP **8.13.0+**. Optionally move to AGP 8.13.x and Gradle 8.13 anyway. Don't jump to AGP 9.x in the same change.
- Install **SDK Platform 36** (Android Studio SDK Manager, or `sdkmanager "platforms;android-36"`).
- Android 16 behavior changes to check on a device:
  - **Edge-to-edge is mandatory** (the opt-out attribute is ignored). `MainActivity` already calls `enableEdgeToEdge()`; check every screen's insets.
  - **Predictive back on by default.** `SlideNav.kt` already uses `PredictiveBackHandler`. Make sure there's no `onBackPressed` override anywhere.
  - **Large screens (sw ≥ 600dp)** ignore orientation and resizability locks. The manifest has none, so check the layout on a tablet or foldable emulator.
- After bumping, run `./gradlew lint bundleRelease`.

### 🔴 1.2 Ship an Android App Bundle signed with the upload key
- New apps must be published as an **AAB**: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- `keystore.properties` (gitignored, in the project root) points at the upload keystore, which is kept **outside the repo**. The password lives only in `keystore.properties` and the maintainer's password manager. **Back up the keystore and its password offline** (password manager plus an encrypted drive).
- Upload certificate SHA-256: `19:66:87:50:A6:13:D1:89:26:22:66:DE:BE:E5:84:E4:67:5B:21:0B:94:3F:7B:CB:3E:84:20:30:63:EC:97:39`
- Accept **Play App Signing** (the default for new apps). Google holds the app-signing key and you hold only the upload key. If you lose the upload key, Play support can reset it after identity checks, but it takes days.
- ⚠️ **Sideloading changes:** with `keystore.properties` present, `assembleRelease` is now signed with the upload key and **won't install over the existing debug-signed sideloaded app** (signature mismatch). For phone test builds, use `./gradlew assembleRelease -PdebugSigning`, or uninstall once. Play-installed builds are signed with Google's app-signing key, which differs from both, so expect one uninstall when switching from the sideloaded build to the Play build.
- The first upload's `versionCode` must be higher than any code you will ever need again. Codes only go up.

### ✅ 1.3 Remove `QUERY_ALL_PACKAGES` (it will not be approved)
**Done:** replaced with the `<queries>` block below.

Play allows it only when the app's **core purpose** requires seeing every installed app: device search, antivirus, file managers, browsers and similar. It is not allowed when "the task can be done with a less broad app-visibility method." Labeling a drain list doesn't qualify.

What the app uses it for: `DrainTracker.label()` → `pm.getApplicationInfo(pkg)`, and `pm.getPackagesForUid()` in `backgroundUse()`. UsageStats and NetworkStats return package names and UIDs **without** any package-visibility permission. Only turning them into app names needs visibility.

Narrowest alternative (manifest only, no Kotlin change):
```xml
<!-- replaces <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" /> -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```
This makes every app with a launcher icon visible, which covers every app a user can open and that can therefore be in the foreground. `label()` already falls back to the package name for anything else (system components). `getPackagesForUid()` returns `null` for invisible UIDs and that row is skipped, which only affects system or headless packages in the "Busy in the background" list. If the declaration is ever needed anyway, don't submit one. Remove the permission.

### ✅ 1.4 Prominent disclosure before sending the user to Usage access
**Done:** `DrainCard.kt` shows this dialog before opening Settings.

App-usage data counts as *personal and sensitive user data* under Play's User Data policy. The current inline text in `DrainCard.kt` ("Allow Usage access so OneBattery can see which app is open while it measures. Nothing leaves your phone.") is close. To be safe, show a **dialog** before `ACTION_USAGE_ACCESS_SETTINGS`, with the disclosure plus explicit **Continue / Not now** buttons:

> **Allow Usage access?**
> To show which apps drain your battery, OneBattery needs to see which app is on screen while it measures, and Android's own totals of each app's background activity and data use. This stays on your phone. OneBattery has no internet access and never shares it. You can turn this off at any time in Settings → Usage access.
> [Not now] [Continue]

`PACKAGE_USAGE_STATS` has **no** Play Console declaration form. It is a special access the user grants in Settings, but the general *Permissions and APIs that access sensitive information* policy still applies.

### 🟢 1.5 Smaller code-level items (not blockers)
- **16 KB page size: already compliant.** The only native library is `libandroidx.graphics.path.so`, which Compose pulls in. Checking v1.6 found ELF `LOAD` segments aligned to 16384 for all four ABIs, and the zip entries stored uncompressed at 16 KB-aligned offsets. Re-check in Play Console → App bundle explorer after the first upload.
- `PowerReceiver` registers `ACTION_POWER_CONNECTED` / `DISCONNECTED` in the manifest. Since Android 8, those implicit broadcasts are not delivered to manifest receivers, so only `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` fire. That's harmless for Play, but the plug/unplug sample never happens. If needed, register those actions at runtime in `MonitorService`.
- One theme is named **"Othello"**, which is a registered board-game trademark. The store listing avoids naming themes. Consider renaming the theme (for example "Harbor") before launch to stay clear of Play's IP and metadata policies.
- Add an **Open-source licenses** screen. OFL font attribution is recommended in-app; see `THIRD_PARTY_NOTICES.md`.

---

## 2. Account-level requirements

### ✅ 2.1 Developer account: Flygon LC organization account (verified)
- **Done:** Flygon LC already has a Play Console **organization** account. Identity, D-U-N-S, website and trader status are all verified, and there are no policy issues. Checked 2026-11-11.
- Organization accounts are **exempt** from the 12-tester, 14-day closed-test rule, so production is open as soon as the app passes review.
- **Package name registration (Android developer verification):** after the first upload, check *Play Console → Developer account → Verification* and confirm `com.flygonlc.onebattery` is registered. Registration is required for global availability as enforcement expands in 2027.
- **Trader re-declaration** is due by **1 Feb 2027**.
- Public contact email (shown on the listing): `info@flygonlc.com`. Website: `https://flygonlc.com`.

---

## 3. Play Console declarations (App content), ready to paste

### 🟡 3.1 Foreground service permissions: `FOREGROUND_SERVICE_SPECIAL_USE`
Required for apps targeting Android 14+. Each FGS type needs a description, the user impact, and a **video link** (an unlisted YouTube video is fine).

**Does the app use foreground services?** Yes → **Special use**

**Description (paste):**
> OneBattery measures the real capacity of the phone's battery (its health) by coulomb counting. It reads the battery current from the fuel gauge (BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) every 10–30 seconds and integrates it over the time it takes the battery level to change by at least 15 percentage points. That usually takes 1–3 hours of charging or use. A capacity result is valid only if readings were taken continuously with gaps under 90 seconds, so the sampling cannot be deferred, batched or scheduled. WorkManager and JobScheduler run at most every 15 minutes, and AlarmManager exact alarms at that rate would wake the device far more often and drain more power. None of the predefined foreground service types fits continuous battery measurement: it is not data sync, location, media, health or a connected device. The service shows a persistent notification with the current battery level, power and temperature. The user can open the app from it, and can stop monitoring by revoking the notification or uninstalling.

**User impact if deferred or interrupted (paste):**
> If the system stops or defers the service, gaps appear in the current readings. Measurements with gaps are discarded by design, because an incomplete integral gives a wrong capacity. The app's core feature, measured battery health, then never produces a result, and the live battery readout in the notification stops updating.

**User-initiated / noticeable:**
> Monitoring starts when the user opens the app. The ongoing notification makes it visible at all times, and it shows live battery data that the user can see change.

**Video (record on a real phone, about 30–60 s):**
1. Open OneBattery. The dashboard shows live level, power and temperature.
2. Pull down the notification shade to show the "OneBattery" ongoing notification updating (e.g. "84% · Charging · +7.9 W · 31°C").
3. Plug in the charger. Show the health card's "Measuring…" progress, and the notification switching to Charging.
4. Show the Settings sheet: notification cadence and "only while screen on" options.
5. Optional: show a completed measurement (capacity in mAh, % of design) and the trend chart.

*Expect a longer review for `specialUse`, which Play treats as a narrow fallback. If it's rejected, appeal with the same text. A clear video is what usually gets these through.*

### 🟡 3.2 Data safety form
Data that is accessed only on the device and never leaves it doesn't need to be disclosed as collected. OneBattery has no INTERNET permission.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | *(not shown when "No" above)* |
| Do you provide a way for users to request that their data is deleted? | *(not shown)* |

This produces the listing label **"No data collected · No data shared"**.
*Backup:* `android:allowBackup="true"` stays on for settings only. `res/xml/data_extraction_rules.xml` (API 31+, cloud backup and device transfer) and `res/xml/backup_rules.xml` (API 29-30) exclude the whole `databases/` domain, so battery history and the per-app usage/drain history never go to Google backup. That keeps the usage-access disclosure ("This stays on your phone") true. What is backed up (SharedPreferences: theme, design capacity, notification options) goes to the **user's own** Google backup, which the developer can't access; Google's Data safety guidance doesn't treat platform backup as developer collection. If you add new data stores, add them to both rule files.

### 🟡 3.3 Privacy policy
Required for every app. Host [`PRIVACY.md`](../PRIVACY.md) at a public, non-PDF URL, either:
- `https://github.com/aribradshaw/onebattery/blob/main/PRIVACY.md` (live now that the repo is public), or
- `https://flygonlc.com/onebattery/privacy` (preferred, because it's stable if the repo moves).

### 🟡 3.4 Other App content forms
| Form | Answer |
|---|---|
| App access | **All functionality is available without special access.** Note for reviewers: *"No login. For the 'What's draining your battery' section, grant Usage access when prompted (Settings → Usage access → OneBattery). Capacity measurement needs 15 percentage points of charging; plug in at about 50% and wait about 30 min to see 'Measuring…' progress."* |
| Ads | **No, my app does not contain ads** |
| Content rating (IARC questionnaire) | Category **Utility, Productivity, Communication or Other**. Answer **No** to every content question (no violence, sexual content, language, controlled substances, gambling, user interaction, data sharing, location sharing, purchases). Expected: **Everyone / PEGI 3 / USK 0**. |
| Target audience and content | **13–15, 16–17, 18+**. Do **not** select under-13 bands, which would put the app under the Families policy. "Appeals to children": No. |
| News app | No |
| Health apps | **Not a health app.** "Battery health" is about the device, not the person; select "My app does not have any health features". |
| Financial features | My app doesn't provide any financial features |
| Government app | No |
| COVID-19 contact tracing/status | No |
| Advertising ID | **No** (the app doesn't use it, and there's no `AD_ID` permission in the merged manifest) |
| Sensitive permissions: QUERY_ALL_PACKAGES | Shouldn't appear once 1.3 is done. If it appears, the old AAB is still on a track. |

### 🟢 3.5 `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `POST_NOTIFICATIONS`
These are normal or runtime permissions with no Console declaration. Starting a `specialUse` FGS from `BOOT_COMPLETED` is still allowed on Android 15/16 (the boot restriction covers dataSync, camera, mediaPlayback, phoneCall, mediaProjection and microphone).

---

## 4. Store listing

Text lives in `fastlane/metadata/android/en-US/`. You can paste it manually, or use `fastlane supply` later (that needs a Play service-account JSON, which must **never** be committed).

| Asset | File | Status |
|---|---|---|
| App name (≤30) | `title.txt`: "OneBattery by Flygon LC" (23 chars) | ✅ Fits. Fallback if Play objects to the developer name in the title: "OneBattery: Battery Health" (26). |
| Short description (≤80) | `short_description.txt` (80 chars) | ✅ |
| Full description (≤4000) | `full_description.txt` (about 2,850 chars) | ✅ No ranking or "best" claims, no emoji, no third-party trademarks except a factual "Samsung Galaxy" mention. |
| Release notes | `changelogs/<versionCode>.txt` | ✅ Rename the file to match the versionCode actually uploaded. |
| Hi-res icon 512×512, 32-bit PNG | `images/icon.png` | ✅ **Flygon** variant (chosen 2026-11-12), full-bleed (Play applies the mask). Alternative: `docs/store-art/icon-system.png`. |
| Feature graphic 1024×500, 24-bit PNG | `images/featureGraphic.png` | ✅ Flygon variant. Alternative: `docs/store-art/featureGraphic-system.png`. |
| Phone screenshots (2–8, 9:16, 1080×1920) | `images/phoneScreenshots/1.png … 7.png` | ✅ Done 2026-11-12: real captures from the S24 Ultra (Flygon theme) in a Flygon-style frame with captions; status bar redrawn because One UI ignores SystemUI demo mode. Raw 1080×2340 captures can't be uploaded as-is (over 2:1). ⚠️ Shot 3 (drain list) shows other apps' names and icons (X, Instagram, Gmail…). That's normal for this kind of app, but if review flags it, recapture with those blurred. |
| 7" / 10" tablet screenshots | `images/sevenInchScreenshots/`, `tenInchScreenshots/` | Optional. Recommended once large-screen layouts are checked (API 36). |
| Category / tags | **Tools** | Tags: Battery, Device info. |

The store art is generated outside the repo from the launcher icons' path data and rendered with a headless browser.

---

## 5. Release sequence

1. ☐ Do 1.1 (API 36), 1.3 (remove QUERY_ALL_PACKAGES) and 1.4 (disclosure dialog). Build, then test on Android 16 and on Samsung.
2. ☐ `./gradlew bundleRelease` with `keystore.properties` present. Confirm with `signingReport` that release uses `onebattery-upload`.
3. ☐ Play Console: create app "OneBattery by Flygon LC", default language en-US, App, Free.
4. ☐ App content: privacy policy URL, app access, ads, content rating, target audience, data safety, FGS declaration plus video, health, financial and government answers.
5. ☐ Store listing: paste text, upload icon, feature graphic and at least 2 screenshots.
6. ☐ Upload the AAB to **Internal testing** and check the pre-launch report (crashes, accessibility, 16 KB).
7. ☐ Personal account: **Closed testing** with 12+ testers for 14 days, then apply for production. Organization account: go straight to Production (staged rollout of 20% is fine).
8. ☐ After approval: tag the release in git (`v<versionName>`) and attach the debug-signed APK, or none, to the GitHub release. Never attach anything signed with the upload key; it's harmless but confusing.

---

## Sources
- Target API level requirements: https://developer.android.com/google/play/requirements/target-sdk · https://support.google.com/googleplay/android-developer/answer/11926878
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16
- AGP and API level compatibility: https://developer.android.com/build/releases/about-agp
- QUERY_ALL_PACKAGES policy: https://support.google.com/googleplay/android-developer/answer/10158779
- Package visibility (`<queries>`): https://developer.android.com/training/package-visibility/declaring
- Foreground service declarations: https://support.google.com/googleplay/android-developer/answer/13392821
- Sensitive permissions policy: https://support.google.com/googleplay/android-developer/answer/9888170
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
- Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Android developer verification: https://developer.android.com/developer-verification/guides · https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console
