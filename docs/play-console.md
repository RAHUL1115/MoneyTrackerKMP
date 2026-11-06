# Play Console: ready-to-paste answers

App: **MoneyTracker** · package `in.amrahul.moneytracker` · personal developer account · SMS permissions declared.

## 1. Create app
- App name: `MoneyTracker`
- Default language: English (United States)
- App or game: **App** · Free or paid: **Free**
- Declarations: tick Developer Program Policies and US export laws.

## 2. App content (Policy › App content)

### Privacy policy
`https://github.com/RAHUL1115/MoneyTrackerKMP/blob/main/PRIVACY.md`

### Ads
**No**, my app does not contain ads.

### App access
**All functionality is available without special access.** (No accounts or logins. The optional passcode is set by the user.)

### Content rating (IARC questionnaire)
- Category: **All other app types** (utility / productivity).
- Violence, sexuality, language, controlled substances, gambling, horror: **No** to all.
- Do users interact or exchange content? **No.**
- Does the app share the user's current location with other users? **No.**
- Does the app allow purchases of digital goods? **No.**
- Expected result: **Everyone / PEGI 3**.

### Target audience and content
- Target age: **18 and over** only.
- Could the app unintentionally appeal to children? **No.**

### News app
**No.**

### Data safety
Everything the user enters stays on the phone. Under Play's definitions, data processed only on the device and never sent off it is not "collected". The app does have the internet permission, used only for the daily exchange-rate update (on by default, only when the user has other currencies, can be turned off): it sends currency codes (for example INR, USD) to api.frankfurter.dev and nothing about the user, their entries or accounts. Currency codes are not one of Play's user data types, so the answer stays No. The web dashboard (off by default) serves the user's own data from the phone to the user's own browser on the same local network; it never reaches the developer or any third party, so it is not "collection" or "sharing" either.
- Does your app collect or share any of the required user data types? **No.**
- (The form then skips the encryption and deletion questions. If it asks, data is not transmitted, and users delete data by uninstalling or using More › Backup & security › Backup & restore › Reset everything.)

### Government apps
**No.**

### Financial features
Tick the option for **personal finance management / budgeting tools** if it's listed. Otherwise choose **"My app doesn't provide any financial features"**. The app moves no money, offers no loans, crypto, trading or banking, and only records what the user enters.

### Health
**No** health features.

### Permissions declaration form (SMS)
- Core functionality: **SMS-based money management**.
- Permissions: `READ_SMS`, `RECEIVE_SMS`.
- Description (paste):

  > MoneyTracker is a personal budget and expense tracker. Its core feature, "Bank SMS detection", reads payment messages from the user's bank and card issuers as they arrive (RECEIVE_SMS) and lets the user import recent ones (READ_SMS) so that income and expenses are recorded automatically: amount, account and category are suggested for the user to confirm. The feature is off by default; a prominent in-app disclosure explains what is read and why before Android's permission prompt. Messages are parsed only on the device and message content is never uploaded (the app's only internet use is a daily exchange-rate update that sends currency codes; the optional web dashboard serves the user's own browser on their local Wi-Fi only) or shared. Non-financial messages are ignored, and users can block senders or words.

- Demo video (30–60 s, unlisted YouTube link). Record on a phone with a bank SMS:
  1. Open More › Bank SMS › Bank SMS settings.
  2. Turn on **Detect bank SMS**; show the in-app disclosure dialog, then Android's permission prompt.
  3. Receive (or send yourself) a bank-style SMS; show the "Spent ₹…" notification.
  4. Open **Detected SMS**, review the suggested entry, Save; show it in the Daily list.
  5. Show **Import SMS** adding older bank messages.

### Foreground service (special use)
App content › Foreground service permissions. `FOREGROUND_SERVICE_SPECIAL_USE` for the web dashboard. Paste:

  > When the user turns on "Web dashboard", MoneyTracker runs a small web server on the phone so the user can view and edit their own entries from a browser on a computer on the same Wi-Fi network. It must keep running while the user works in that browser with the phone in their pocket or the screen off, which no other foreground-service type covers (it isn't data sync, media or connected-device communication). It starts only from the user's tap, shows an ongoing notification with Stop, stops automatically after 30 minutes without visits (user-adjustable) or when the phone leaves the Wi-Fi, and serves only the local network.

- Demo video (30 s): More › Web dashboard › Start; show the notification; open the address on a computer, add an entry; tap Stop in the notification.

### Location
`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`: foreground only, used when "Save location" is on (an in-app disclosure is shown first). No background location, so no extra declaration is needed.

## 3. Store presence
- Main store listing: title, short and full description from `fastlane/metadata/android/en-US/`.
- App icon: `fastlane/metadata/android/en-US/images/icon.png` (512 × 512).
- Feature graphic: `.../images/featureGraphic.png` (1024 × 500).
- Phone screenshots: `.../images/phoneScreenshots/1_daily.png` … `6_detected.png` (1080 × 2137, retaken 2026-11-05 at Galaxy S24 size and density with the status and navigation bars trimmed to fit Play's 2:1 limit; made-up demo data). Regenerate with `DemoData.kt` (see its header) if the UI changes.
- App category: **Finance**. Tags: budget, expense tracker, personal finance.
- Contact email: required (shown publicly). Website: `https://github.com/RAHUL1115/MoneyTrackerKMP`.

## 4. Releases
1. **Internal testing** first: upload the `play-bundle` AAB from the CI run of the latest tag. **Before the first upload**, set up app signing with **your own key** (App integrity › App signing › "Use a different key" › export and upload the release keystore with Google's PEPK tool). Don't accept the Google-generated key: Play installs would then be signed differently from the GitHub/F-Droid APKs, and users couldn't update across them. This can't be changed later.
2. **Closed testing** (required for personal accounts created after Nov 2023): at least **12 testers** opted in for **14 consecutive days**. Add them by email list or Google Group and share the opt-in link.
3. Then **Apply for production** on the Dashboard; answer the questions about the closed test.
4. Each later release: bump `versionCode` (+1) and `versionName`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, tag `v<versionName>`.

## 5. Things that commonly cause rejection
- SMS declaration without a clear video, or without the disclosure shown before the permission prompt.
- Store text naming another app's brand prominently. "Import .mmbak backups" is fine; don't put "Money Manager" in the title, short description or graphics.
- Missing or unreachable privacy policy link.
