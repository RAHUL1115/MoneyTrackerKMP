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
Everything stays on the phone and the app has no internet permission. Under Play's definitions, data processed only on the device and never sent off it is not "collected".
- Does your app collect or share any of the required user data types? **No.**
- (The form then skips the encryption and deletion questions. If it asks, data is not transmitted, and users delete data by uninstalling or using More › Backup › Reset everything.)

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

  > MoneyTracker is a personal budget and expense tracker. Its core feature, "Bank SMS detection", reads payment messages from the user's bank and card issuers as they arrive (RECEIVE_SMS) and lets the user import recent ones (READ_SMS) so that income and expenses are recorded automatically: amount, account and category are suggested for the user to confirm. The feature is off by default; a prominent in-app disclosure explains what is read and why before Android's permission prompt. Messages are parsed only on the device. The app has no internet permission, so message content is never uploaded or shared. Non-financial messages are ignored, and users can block senders or words.

- Demo video (30–60 s, unlisted YouTube link). Record on a phone with a bank SMS:
  1. Open More › Configuration › Bank SMS & notifications.
  2. Turn on **Detect bank SMS**; show the in-app disclosure dialog, then Android's permission prompt.
  3. Receive (or send yourself) a bank-style SMS; show the "Spent ₹…" notification.
  4. Open **Detected SMS**, review the suggested entry, Save; show it in the Daily list.
  5. Show **Import SMS** adding older bank messages.

### Location
`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`: foreground only, used when "Save location" is on (an in-app disclosure is shown first). No background location, so no extra declaration is needed.

## 3. Store presence
- Main store listing: title, short and full description from `fastlane/metadata/android/en-US/`.
- App icon: `fastlane/metadata/android/en-US/images/icon.png` (512 × 512).
- Feature graphic: `.../images/featureGraphic.png` (1024 × 500).
- Phone screenshots: `.../images/phoneScreenshots/1_daily.png` … `6_detected.png` (1080 × 2137, Galaxy S24 size and density with the status and navigation bars trimmed to fit Play's 2:1 limit; made-up demo data). Regenerate with `DemoData.kt` (see its header) if the UI changes.
- App category: **Finance**. Tags: budget, expense tracker, personal finance.
- Contact email: required (shown publicly). Website: `https://github.com/RAHUL1115/MoneyTrackerKMP`.

## 4. Releases
1. **Internal testing** first: upload the `play-bundle` AAB from the CI run of the latest tag. On first upload, accept **Play App Signing**; the CI release key becomes the upload key.
2. **Closed testing** (required for personal accounts created after Nov 2023): at least **12 testers** opted in for **14 consecutive days**. Add them by email list or Google Group and share the opt-in link.
3. Then **Apply for production** on the Dashboard; answer the questions about the closed test.
4. Each later release: bump `versionCode` (+1) and `versionName`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, tag `v<versionName>`.

## 5. Things that commonly cause rejection
- SMS declaration without a clear video, or without the disclosure shown before the permission prompt.
- Store text naming another app's brand prominently. "Import .mmbak backups" is fine; don't put "Money Manager" in the title, short description or graphics.
- Missing or unreachable privacy policy link.
