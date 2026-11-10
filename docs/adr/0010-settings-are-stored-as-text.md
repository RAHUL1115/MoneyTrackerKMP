# Settings are a key–text table, read through typed definitions

Settings live in one `Setting` table as key → text, inside the database, so they travel with backups. The app reads and writes them through `Prefs`, which declares each setting's type and default once. The stored text formats are frozen ("1"/"0" for on/off, enum names or short codes, "HH:mm" for times), because backups and the Money Manager import and export read and write them directly. A new setting needs a new key; changing what an existing key's text means needs a migration.

## Considered Options

- **Android DataStore or SharedPreferences.** Rejected: settings would not be in the database file, so backups would lose them.
- **A column per setting.** Rejected: every new setting would need a schema migration.
