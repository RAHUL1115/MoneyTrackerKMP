# The passcode travels with the backup

The passcode is stored as a salted hash inside the database, with its salt, so a backup restored on any phone (the same phone, a new one, or after a reinstall) opens with the passcode it was made with. The app does not ask anyone to sign in again. Nothing tied to one install (Android Keystore keys, salts kept outside the database) may be used to check the passcode, or restored backups would lock their owners out. Older backups whose hash needed the install's own salt are handled by keeping the passcode in use before the restore as a one-time fallback.
