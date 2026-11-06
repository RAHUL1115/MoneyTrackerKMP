# Privacy policy

_Last updated: 7 November 2026_

MoneyTracker is an offline app. It has no servers, no accounts, no ads and no analytics, and it never sends your entries, accounts or other data anywhere by itself.

## Exchange rates
If you add other currencies (for example a dollar account), the app asks api.frankfurter.dev, a free open-source service that publishes the European Central Bank's daily reference rates, for today's rates: once a day when you open the app, and whenever you tap Update now. If you have no other currencies, nothing is requested. The request contains only your currency codes (for example INR and USD). Like any web request it reveals your IP address to that service. To stop the daily check, turn off Settings › Money › Other currencies › Update automatically; you can still type rates by hand. Entries you already saved keep the rate they were entered with.

## Web dashboard
If you turn on More › Web dashboard (Settings › Web dashboard in the new layout), the phone serves a web page to computers on the **same Wi-Fi network**, so you can see and edit your entries in a browser. The page and your data travel only between your phone and that computer over your local network; nothing goes to the internet, to us or to anyone else. The phone listens only on its Wi-Fi (or hotspot) address, refuses requests from outside the local network, and the page loads nothing from the internet.

You can set a password, which is stored on the phone only as a salted hash; after 5 wrong tries a computer has to wait a minute, and a computer is signed out after 30 minutes idle. Without a password, anyone on the same Wi-Fi who knows the address can open it, so set one on shared or public Wi-Fi. The connection is plain HTTP inside your network, like most home devices, so other people on an untrusted network could watch the traffic: use it on Wi-Fi you trust. The dashboard runs only while you have it on, shows an ongoing notification, and stops by itself after the time you choose (30 minutes by default) with no visits, or when the phone leaves the Wi-Fi.

## What the app stores
Everything you enter (accounts, entries, categories, budgets, notes, tags, receipt photos, settings) is stored only in the app's private storage on your phone. Daily automatic snapshots are kept there too (the last 10).

Data leaves the app only when **you** export or share a backup, CSV or Excel file; it goes where you choose in the system file picker or share sheet.

The app is excluded from Android's cloud backup, so nothing is copied to your Google account. When you set up a new phone by copying directly from the old one (cable or Wi-Fi), Android 12 and later may bring the app's data across; otherwise, export a backup file and restore it on the new phone (More › Backup & security › Backup & restore).

Uninstalling the app deletes its data, except files you exported.

## Permissions
Every permission is used only for the feature it belongs to; all except Internet are requested only when you turn that feature on.

| Permission | Used for |
|---|---|
| Internet | The daily exchange-rate update when you use other currencies (can be turned off; the request contains currency codes and nothing else), and the web dashboard on your local Wi-Fi while you have it on. |
| Foreground service, keep awake | Keeping the web dashboard running, with its notification, while you have it on. |
| Location (precise or approximate) | Attaching where you were to a new entry, when "Save location" is on. Only latitude and longitude are saved with that entry; there is no background location. |
| SMS (receive and read) | Bank SMS detection, when turned on, and "Import SMS": bank messages are read on the phone to suggest entries; other messages are ignored. Messages are never uploaded, shared or sent anywhere. |
| Notifications | Daily reminder, quick-add notification, detected-SMS alerts and the "Web dashboard is on" notification. |
| Run at startup | Restoring the reminder after the phone restarts. |
| Fingerprint / face | Unlocking the app when a passcode is set. The app never sees your biometric data; Android only reports success or failure. |

The camera, voice input and maps are used through other apps on your phone (camera app, speech recognizer, map app). What those apps do is covered by their own privacy policies; for example, a speech recognizer may process audio online.

## Passcode
The passcode is stored only as a salted hash. A passcode imported from a Money Manager backup is hashed the same way.

## Children
The app does not collect any data from anyone, including children.

## Changes and contact
Changes to this policy are made in this file, and its history is public in the repository. Questions: open an issue on the project's GitHub page.
