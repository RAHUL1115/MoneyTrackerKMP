# Everything stays on the phone

There is no account, server or cloud sync: the data lives in one SQLite file on the phone, and moving it between phones is done with a backup file. People use this app to record their own spending, and the strongest privacy promise is that the data never leaves the device. Only two network uses are allowed: the daily exchange-rate update (it sends only currency codes, and can be turned off) and the opt-in web dashboard, which serves the local Wi-Fi only and loads nothing from the internet. Any other network use needs discussion first, and it also changes the privacy policy and the store's data-safety answers.

## Considered Options

- **Cloud sync between devices.** Rejected: it needs a server, accounts and a privacy policy that covers stored financial data.
- **No network at all.** This was the original stance. It was relaxed for exchange rates after typed-in rates went stale.
