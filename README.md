# MoneyTracker

**Track your money on your phone, without signing up or using a cloud service.**

MoneyTracker is a free, open-source personal-finance app for **Android 7.0 and newer**. Record expenses, income and transfers, manage bank accounts and credit cards, and see where your money goes. No ads, no sign-up, no analytics, and no internet permission.

**[Download the latest APK](https://github.com/RAHUL1115/MoneyTrackerKMP/releases/latest)** · [Getting started](#getting-started) · [SMS detection](#bank-sms-detection) · [Backups](#back-up-your-data) · [For developers](#for-developers)

## Download and install

You do **not** need Android Studio or any developer tools to use the app.

1. Open the [latest GitHub release](https://github.com/RAHUL1115/MoneyTrackerKMP/releases/latest) on your Android phone.
2. Under **Assets**, download the file named **`MoneyTracker-v<version>.apk`**. Choose the APK, not GitHub's “Source code” ZIP or TAR files.
3. Open the downloaded APK. If Android asks, allow your browser or file manager to **install unknown apps**, then continue with the installation. You can turn that permission off afterward.
4. Open MoneyTracker and choose your currency.

**Updating:** download and install a newer release APK over your existing release install. There is no need to uninstall first; uninstalling deletes the app's local data. Export a backup before updating or moving phones.

**Optional download check:** releases include a matching `.apk.sha256` file. Compare its checksum with the SHA-256 of the downloaded APK to check that the file downloaded intact. Get both files from this repository's release page.

## Getting started

1. **Set up your accounts.** In **Accounts**, add the places you keep or owe money: cash, bank accounts, savings and credit cards. Put a credit card in the **Credit Cards** group, not Bank Accounts. Use balance adjustment if you need to set an existing balance.
2. **Add your first entry.** From **Trans.**, tap **+**, choose **Expense**, **Income** or **Transfer**, and enter the amount, account and category. Add a note if useful, then save.
3. **Check your spending.** Use **Daily**, **Calendar** and **Monthly** to browse entries. **Stats** shows spending by category. Set budgets from **More** when you are ready.
4. **Make a backup.** Open **More › Backup › Export file** and save a copy somewhere outside the app.

You can use MoneyTracker entirely by entering transactions yourself. SMS detection, notifications, location and other optional features can stay off.

## What you can do

- **Daily bookkeeping:** record income, expenses and transfers with a built-in keypad; copy entries or use bookmarks for repeat purchases.
- **Accounts and cards:** see balances and net worth, track credit-card billing cycles and spending limits, link debit cards to bank accounts, and use different currencies per account.
- **Budgets and reports:** set overall, category or sub-category budgets; explore monthly/yearly spending and search by note, tag, amount or date.
- **Recurring payments:** schedule repeat entries with an end date or split credit-card spending into monthly installments.
- **More detail when you need it:** notes, tags, receipt photos, optional location tags and voice entry.
- **Faster access:** home-screen widgets, launcher shortcuts and Quick add buttons in the notification shade.
- **Your preferences:** light/dark appearance, wallpaper-based accent colours, custom month start, passcode and biometric unlock, and 79 languages including right-to-left scripts.
- **Import and export:** backup files, CSV and Excel (`.xlsx`), plus Money Manager (`.mmbak`) backup import.

## Bank SMS detection

MoneyTracker can read bank transaction SMS messages **on your phone** and suggest entries. Messages are not uploaded to a server.

### Turn it on

1. Open **More › Configuration › Bank SMS**.
2. Enable **Detect bank SMS** and grant SMS permission when asked.
3. Leave **Save automatically** off initially. New detected transactions wait under **More › Detected SMS**, where you can check the amount, account and category before adding them.
4. Enable **Alert for detected messages** if you want a notification when a transaction is detected. Grant notification permission when asked.

You can also import recent SMS messages from the Bank SMS settings, or use **Paste message** in the detected-transactions inbox.

### Check which account is selected

A bank can issue both a bank account and a credit card. MoneyTracker uses the message's account/card wording, identifying digits, configured rules and learned corrections to suggest the right one. For example, an HDFC credit-card spend should select your HDFC card, while an HDFC account debit should select your bank account—even when both messages use the same sender ID.

For more precise matching, edit an account's **Bank messages** fields and enter identifying text such as `x1234`, optionally with a sender ID. Avoid a broad rule such as only the bank's name when several accounts share it. Corrections made when saving detected messages help future account/category suggestions.

**Detection is an assistant, not a guarantee.** Bank formats vary, some messages are ambiguous, and not every message will be recognised. Review suggestions before saving. If you later turn on **Save automatically**, recognised-account transactions can be added without review; check the results regularly.

### Notification groups

There are three notification types, each with its own group and Android notification channel:

| Type | What it does |
| --- | --- |
| **Detected transactions** | Bundles all detected-SMS alerts together, regardless of bank, account or card. Incoming SMS alerts use default priority and the notification sound; the group summary does not play a second sound. |
| **Daily reminder** | Reminds you to record spending at your chosen time. |
| **Quick add** | Keeps silent buttons for adding income, expenses or transfers in the notification shade. |

Enable the reminder and Quick add in **More › Configuration**. Android's notification settings control each channel's sound and visibility. If SMS alerts are silent, check **Android Settings › Apps › MoneyTracker › Notifications › Detected transactions**, notification volume and Do Not Disturb. Device-specific menus may differ.

## Back up your data

**Your data lives on your phone. There is no cloud account or automatic cloud sync.**

- **Automatic snapshots:** the app keeps the last 10 daily snapshots in its private storage. You can also use **More › Backup › Back up now**.
- **A copy outside the app:** use **More › Backup › Export file** and choose where to save it. Keep an exported copy before uninstalling, resetting the phone or changing devices.
- **Restore:** use **More › Backup › Restore file** and choose your backup. The app validates it and takes a snapshot of the current data before restoring.
- **Spreadsheets:** use CSV or Excel export/import when you need to work with entries outside the app.

**Uninstalling deletes the app's data and its internal snapshots. Only files you exported outside the app remain.** See the [privacy policy](PRIVACY.md) for storage and Android device-transfer details.

## Switching from Money Manager

In Money Manager, export a backup file (`.mmbak`), then open **More › Backup › Restore file** in MoneyTracker and choose it. Backups from Money Manager on Android and iPhone are supported: accounts, entries, categories, budgets, repeats, settings and passcode can be imported.

Your current MoneyTracker data is snapshotted first, so the restore can be undone. Keep the original backup and check your balances and imported entries afterward. An imported passcode comes across too.

MoneyTracker is an independent app, not affiliated with Realbyte. You do not need to have used Money Manager to use it.

## Privacy and help

MoneyTracker itself has no internet permission, ads, sign-up or analytics. Everything stays on your phone unless you choose to export or share it. Camera, speech-recognition and map features use other apps on your phone; those apps have their own privacy policies. See [PRIVACY.md](PRIVACY.md).

For in-app guidance, open **More › Help**. To report a bug or request a feature, [open an issue](https://github.com/RAHUL1115/MoneyTrackerKMP/issues). For SMS problems, include the app version and an example with account numbers, references, phone numbers and other personal details removed. Report security problems privately as described in [SECURITY.md](SECURITY.md).

---

## For developers

The sections above are for people installing and using the app. The sections below cover building, testing and maintaining it.

### Stack and supported target

MoneyTracker uses **Kotlin Multiplatform + Compose Multiplatform**. The domain logic, SQLDelight database and UI live in `commonMain`; the currently configured target is Android. This shared structure allows iOS or desktop targets to be added later, but this repository does not currently provide those apps.

The app stays offline: no network access, analytics, ads or proprietary dependencies. Read [CONTRIBUTING.md](CONTRIBUTING.md) before making changes.

### Build and test

Open the repository in Android Studio with an Android SDK configured, or use the checked-in Gradle wrapper:

```sh
./gradlew :composeApp:assembleDebug          # build an APK
./gradlew :composeApp:installDebug           # install on a connected device/emulator
./gradlew :composeApp:testDebugUnitTest      # parser, domain and SQLite repository tests
./gradlew :composeApp:assembleDebugAndroidTest  # build the instrumented-test APK
```

Debug APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

Debug builds use `in.amrahul.moneytracker.debug` and install separately from the release app. The public debug key must never sign a release. A debug install is not an update to a release install, and their data is separate.

For Android notification tests and incoming-SMS smoke tests, follow [SMS detection test instructions](docs/sms-detection-tests.md). **Use a disposable test installation or the documented in-place `adb` commands:** Gradle's connected-test cleanup can uninstall the target app and remove its data.

### Project layout

```text
composeApp/src/
  commonMain/
    sqldelight/.../Money.sq      schema + SQL (balances, reports, budgets, recurring)
    kotlin/app/moneytracker/
      domain/                   entities, money rules, SMS parsing and matching
      data/                     repository, backups and import/export
      ui/                       Compose screens
  androidMain/                  Android platform integrations, SMS receiver, notifications, widgets
  commonTest/                   domain/parser tests and public-format SMS fixtures
  androidUnitTest/              SQLite-backed repository tests
  androidInstrumentedTest/      device notification tests and opt-in telephony smoke test
```

### Core rules

- Amounts are `Long` minor units. A transaction's `kind` gives its sign: INCOME, TRANSFER_IN and ADJUST_UP add to the balance; EXPENSE, TRANSFER_OUT and ADJUST_DOWN subtract.
- A transfer is two rows (OUT + IN) plus an optional fee row, all sharing `transferGroupId`. Delete removes all of them.
- Installments (credit-card expenses) are N monthly rows sharing `installmentGroupId`. The first row takes the rounding remainder.
- Recurring rules materialise on app start and after a save. IDs are deterministic (`rule_epochDay`), so a rule never creates duplicates. The end date is honoured.
- Transfers into an account flagged “transfer counts as expense” (savings, investments) are expenses; transfers back out are income.
- Credit-card cycles start on the settlement day. Payments settle the oldest charges first, giving overdue, statement due and current cycle.
- Budgets are monthly per main category (or a total) and take effect from a month onward.
- Schema changes need migrations so older backups remain restorable. Restore validates a file and takes a safety snapshot before replacing data.

### Releasing

1. Bump `versionCode` (by at least 1) and `versionName` in `composeApp/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with the release notes.
3. Create a signed-off commit, tag `v<versionName>` (for example `v0.2.0`) and push the tag.

CI builds the APK signed with the release key and publishes it as a GitHub release. The same tag, version and store text (`fastlane/metadata`) serve F-Droid, IzzyOnDroid and Google Play. Every distribution channel is intended to ship APKs signed with the same release key so users can move between them without reinstalling. See [Play Console notes](docs/play-console.md) for store setup.

### Contributing and security

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md), including the DCO sign-off requirement. Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

MoneyTracker is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License v3.0](LICENSE). It is distributed without any warranty. Bundled libraries are under the Apache License 2.0; the app lists them in **More › About**.

“Money Manager” is a trademark of Realbyte Inc. This project is not affiliated with or endorsed by Realbyte; the name is used only to describe backup compatibility.
