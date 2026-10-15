# MoneyTracker (Kotlin Multiplatform + Compose Multiplatform)

An offline personal-finance app for Android. All domain logic, the database and the UI live in `commonMain`, so iOS or desktop targets can be added later.

**Private by design:** no ads, no accounts, no analytics and no internet permission. Everything stays on your phone; see [PRIVACY.md](PRIVACY.md).

## Why MoneyTracker?
MoneyTracker is an independent app inspired by the quick, calendar-style bookkeeping of Money Manager by Realbyte. If you've used it, you'll feel at home; if you haven't, you don't need to: just start adding entries.

- **Open source** under the GPL v3: anyone can read, check and improve the code.
- **Fully offline:** no internet permission at all, no ads, no sign-up, no analytics.
- **Fast daily bookkeeping:** keypad entry, Daily / Calendar / Monthly views, budgets and credit-card cycles.
- **Smarter entry:** on-device bank-SMS detection that learns your categories, voice entry, and note-based category suggestions.
- **Modern Android:** Material You accent from your wallpaper, dark mode, home-screen widgets, precise location tags.
- **Easy to switch:** restores Money Manager backups (`.mmbak`) from Android and iPhone.
- **79 languages**, including right-to-left scripts.

## Install
Download the latest APK from [Releases](../../releases) and open it on your phone (allow installs from your browser or file manager when asked). Each release lists the APK's SHA-256 checksum.

## Switching from Money Manager
More › Backup › Restore file reads Money Manager backups (`.mmbak`) from Android and iPhone: accounts, entries, categories, budgets, repeats, settings and passcode come across. Your current data is snapshotted first, so a restore can be undone.

## Features
- **Entry:** income/expense/transfer with a custom keypad, category and account grids, transfer fees, card installments, repeat with end date, Save + Continue, copy, bookmarks (templates), tags, receipt photos, location, voice entry ("spent 250 on lunch"), and a learned category suggestion from the note.
- **Views:** Daily, Calendar (tap a day for its list), Monthly with weeks, Total (budget, spending split, CSV export), Notes, Search (text/tag/amount/date range). Carry-over is optional.
- **Stats:** monthly/yearly pie and list, drill-down into sub-categories.
- **Accounts:** groups, net worth, per-account currency, credit-card cycle (payable/outstanding/upcoming), card spending limits, debit cards linked to banks, balance adjustment, restore deleted accounts.
- **Budgets:** total, per category and per sub-category, from a month onward.
- **Settings:** month start day with weekend shift, week start, start screen, card expenses at purchase vs payment, sub currencies with rates, tags, passcode + fingerprint, daily reminder, quick-add notification, bank-SMS detection, location.
- **Data:** daily automatic snapshots, manual snapshots, full-file export/import, CSV and Excel (.xlsx) export and import, Money Manager (`.mmbak`) import. Schema migrations keep old backups restorable.
- **Android extras:** home-screen widget (month totals + quick add), launcher shortcuts, notification quick add.
- **SMS assistant:** regex parser for bank, amount, card digits and vendor, plus a tiny Naive Bayes learner stored in SQLite. Detected messages become suggestions in an inbox; nothing is saved without you, and each save teaches it. You can also paste a message.

No ads, no network access, no cloud.

## Run
```
./gradlew :composeApp:installDebug          # on a connected device or emulator
./gradlew :composeApp:testDebugUnitTest     # domain + repository tests (in-memory SQLite)
```
Open the folder in Android Studio to run it on an emulator.

## Layout
```
composeApp/src/
  commonMain/
    sqldelight/.../Money.sq      schema + all SQL (balances, report rows, budgets, recurring)
    kotlin/app/moneytracker/
      domain/   Model.kt         entities, enums (TxnKind, AccountType, RepeatType), YearMonth
                Rules.kt         periods (custom month start), recurrence, installments,
                                 income/expense classification, card statement (FIFO), budgets, net worth
                Money.kt         minor-unit money formatting and parsing
      data/     MoneyRepository  writes (transfer pairs, fees, installments, recurring generation), flows
                Backup.kt        BackupController contract + CSV exporter
      ui/                        Compose screens: Transactions, Add/Edit, Stats & Budget, Accounts, More
  androidMain/                   MainActivity, DatabaseManager (open/close/swap), AndroidBackupController
  commonTest/ androidUnitTest/   tests
```

## Core rules
- Amounts are `Long` minor units. A transaction's `kind` gives its sign: INCOME, TRANSFER_IN and ADJUST_UP add to the balance; EXPENSE, TRANSFER_OUT and ADJUST_DOWN subtract.
- A transfer is two rows (OUT + IN) plus an optional fee row, all sharing `transferGroupId`. Delete removes all of them.
- Installments (credit-card expenses) are N monthly rows sharing `installmentGroupId`. The first row takes the rounding remainder.
- Recurring rules materialise on app start and after a save. Ids are deterministic (`rule_epochDay`), so a rule never creates duplicates. The end date is honoured.
- Transfers into an account flagged "transfer counts as expense" (savings, investments) are expenses; transfers back out are income.
- Credit card: the cycle starts on the settlement day. Payments settle the oldest charges first, giving overdue, statement due and current cycle.
- Budgets are monthly per main category (or a total) and take effect from a month onward.
- Backup: daily automatic snapshots in app storage (10 kept), back up now, export or import through the system file picker, and CSV export. Restore validates the file and takes a safety snapshot first.

## Releasing
1. Bump `versionCode` (by at least 1) and `versionName` in `composeApp/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with the release notes.
3. Commit, tag `v<versionName>` (for example `v0.2.0`) and push the tag.

CI then builds the APK signed with the release key and publishes it as a GitHub release. The same tag, version and store text (`fastlane/metadata`) serve F-Droid, IzzyOnDroid and Google Play, and every channel is meant to ship APKs signed with this one key (F-Droid through reproducible builds, Google Play by enrolling it as the app signing key), so users can move between them without reinstalling.

## Contributing and security
Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License
MoneyTracker is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License v3.0](LICENSE). It is distributed without any warranty. Bundled libraries are under the Apache License 2.0; the app lists them in More › About.

"Money Manager" is a trademark of Realbyte Inc. This project is not affiliated with or endorsed by Realbyte; the name is used only to describe backup compatibility.
