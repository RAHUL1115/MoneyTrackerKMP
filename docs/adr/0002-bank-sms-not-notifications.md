# Payments are detected from bank SMS only, never from other apps' notifications

Detected payments come from the SMS receiver and from reading the inbox to catch up. The app does not use a notification listener, so it never reads other apps' notifications, even though that would also catch payments made in bank and UPI apps. Notification access lets an app see every message, chat and one-time code on the phone, which is far beyond what a money tracker should hold, and the store review for it is strict. The app may post its own notifications (reminders, quick add, detected payments).
