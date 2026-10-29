# SMS detection regression checks

## Automated checks

```sh
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
```

- `SmsParserTest` checks extraction and instrument classification independently of money direction (a credit to a bank account is not a credit-card transaction).
- `SmsAccountMatchingTest` exercises ingestion through the real SQLite repository and checks the account suggested in the editor. It covers same-bank accounts/cards, shared senders, known digits, learned suggestions, ambiguity, and auto-save safety.
- Synthetic examples contain invented identifiers. The Slice UPI credit-card format was supplied by the user; its amount, merchant, suffix, reference, and phone number are synthetic.

## Public-format corpus

`composeApp/src/commonTest/kotlin/app/moneytracker/PublicSmsFixtures.kt` holds 11 source-linked examples from six banks. Each is checked for amount, bank, suffix, instrument, and refund direction; the repository test replays every example with same-bank bank, credit-card, and debit-card accounts present and checks both ingestion and editor selection.

These are **public open-source test examples, not independently authenticated bank-issued messages**. Some upstream examples are explicitly synthetic/masked. No parser implementation or ledger expectations were copied; expected behavior is defined by this app's rules. Sources are pinned to retrieved commits:

| Formats | Source |
| --- | --- |
| HDFC UPI account debit | [PennyWise HDFC tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/TestHDFCBankParser.kt) |
| HDFC bare-card spend and unidentified spend | [Card fixture](https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/tests/fixtures/hdfc/07-credit-card-spend.json), [unidentified spend](https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/tests/fixtures/hdfc/08-debit-card-spend.json) |
| ICICI card spend with available limit; card refund | [Spend fixture](https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/tests/fixtures/icici/01-card-spend.json), [refund fixture](https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/tests/fixtures/icici/04-refund.json) |
| ICICI debit with counterparty credited | [PennyWise ICICI tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/TestICICIBankParser.kt) |
| SBI UPI without currency marker; explicit debit card | [UPI fixture](https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/tests/fixtures/sbi/01-upi-debit-no-currency-marker.json), [PennyWise SBI tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/TestSBIParser.kt) |
| Axis multiline card; Kotak credit card over UPI | [Axis tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/TestAxisBankParser.kt), [Kotak tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/TestKotakBankParser.kt) |
| Slice credit card over UPI, SLCBNK sender | [PennyWise Slice tests](https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/com/pennywiseai/parser/core/bank/SliceParserTest.kt) |

Transformations: support numbers and identifying-looking references were replaced with dummy values, the Kotak suffix/merchant were anonymized, and unrelated footer text was omitted from some excerpts. The HDFC credit-card fixture deliberately retains conflicting body/footer suffixes to check that blocking instructions do not override the transaction identity. The HDFC spend without a body account/card identity deliberately remains unassigned; footer `DC` text alone is not treated as a reliable account identifier. Bank-recognition tests additionally remove the bank name from the body to exercise DLT sender suffixes (`-S`) directly.

Scope: these tests focus on account routing and basic parsed fields. They do not establish complete merchant extraction, all bank formats, or card-repayment/transfer detection. In particular, the upstream card-bill acknowledgement example was not used to define income/transfer semantics here.

## Notification device checks

The Android instrumented tests need a connected device/emulator. **Do not run `connectedDebugAndroidTest` on a data-bearing installation:** the Gradle device-test runner was observed uninstalling the debug target and removing its data during cleanup. Use a disposable VM, or explicitly back up the app first. For in-place testing without automatic uninstall:

```sh
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
adb -s <serial> install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s <serial> install -r composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  -e class app.moneytracker.NotificationsTest \
  in.amrahul.moneytracker.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in `SmsDeliverySmokeTest` adds synthetic HDFC Bank / HDFC Credit Card accounts and leaves pending captures/notifications for manual inspection. Run it only on an unlocked test installation with notification/SMS permissions granted and Bank SMS detection enabled through the app. It changes auto-save/alert/Quick add settings and clears existing debug-app detected notifications for the smoke check. It injects GSM-7 class-1 SMS-DELIVER PDUs through Android's telephony API using the test runner's adopted shell permission; it does not call `ingestMessage` or the receiver directly. This is emulator telephony delivery, not a carrier-network test. Never add these elevated permissions to the production app.

```sh
adb -s <serial> shell am instrument -w \
  -e class app.moneytracker.SmsDeliverySmokeTest -e smsSmoke true \
  in.amrahul.moneytracker.debug.test/androidx.test.runner.AndroidJUnitRunner
# Retrigger a single credit-card SMS for a human sound check: add -e smsCount 1.
```

Run the channel-default test on a **fresh debug install**. Do not clear a real user's app data to run it. It checks three channels, detected-channel sound/default importance, one shared detected group for all SMS alerts, a non-alerting group summary, and legacy notification sound/default priority.

Also smoke-test notification-shade behavior on a device:

1. Grant notification permission and enable SMS alerts and Quick add.
2. Import or receive several different transaction SMS messages, including one automatically saved and one awaiting review.
3. Confirm all detected SMS alerts bundle together in one Detected transactions group, regardless of bank/account/card. Quick add and the daily reminder must each have their own separate group; neither belongs inside the detected-SMS bundle.
4. Confirm the default notification sound plays when the device permits sound; test with DND off, audible notification volume, and no user-imposed channel mute.
5. Tap a detected transaction and confirm it opens the inbox.
6. Change Detected transactions to silent in Android settings, reopen the app, and confirm the app preserves that choice.

A notification channel controls importance and sound on Android 8+. Recreating a channel with the same ID does **not** override an existing user's sound/importance settings. If the existing channel is silent, change it under Android Settings → Apps → Money Tracker → Notifications → Detected transactions. App defaults cannot bypass DND, notification permission, device volume, or OEM notification policies.

The notification configuration tests assert builder/channel configuration; actual audible sound and OEM notification-shade grouping still require the device smoke checks above.

### Verified Genymotion smoke result

On the existing Genymotion Phone, Android 15/API 35:

- All three `NotificationsTest` device tests and the telephony smoke test passed (four device tests total).
- Four synthetic incoming `AD-HDFCBK-S` SMS PDUs passed through Android telephony: two explicit credit-card spends selected HDFC Credit Card and two account debits selected HDFC Bank. Both accounts had the same HDFCBK sender rule.
- All four debug-app SMS alerts shared the `detected` group key and importance 3/default. A single summary bundled them together. The app had exactly three notification groups: `detected`, `quick_add`, and `reminder`; grouping is never based on bank, account, card, or capture ID.
- All four SMS children produced Android audible-alert events. The detected summary produced none, avoiding a second sound when the bundle updates.
- Android notification-alert events recorded audible alerts for debug-app detected notifications; the audio service recorded notification playback. A further single credit-card SMS was triggered, and the user confirmed the sound was audible.
- Tapping a detected notification opened the inbox. Opening each kind's Add action showed HDFC Credit Card or HDFC Bank respectively in the real editor; no draft was saved.
- A separate legacy `app.moneytracker` install also received the injected SMS and posted its old bundled notifications. Those must not be confused with the updated `in.amrahul.moneytracker.debug` notifications. The legacy app was not modified.

The initial Gradle connected-test run unexpectedly uninstalled the debug app and removed its local data. Subsequent testing used explicit `adb install -r` and `am instrument`; the debug app remained installed. Synthetic test fixtures remain available in its inbox for inspection.
