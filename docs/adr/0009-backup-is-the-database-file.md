# A backup is a copy of the database file

A backup (`.mtbak`) is a complete copy of the SQLite database, not an export in some other format. Restoring checks the file, saves a snapshot of the current data, then replaces the database file while it is closed and opens it again. Nothing has to be translated, and settings, passcode and every table come along. The cost is that schema changes need SQLDelight migrations (`N.sqm`) for as long as old backups exist, since a backup from any earlier version must still restore and upgrade.
