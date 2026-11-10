# Repeats become real entries when they fall due

A repeat does not produce entries on the fly for reports. It writes real entries when they fall due, each time the app opens or saves, either on the date itself or at the start of the month, depending on the setting. Each entry's id is made from the repeat and the date, so running the check twice never adds duplicates. Once an entry has been added it is an ordinary entry, so people can edit or delete that one occurrence, and every report, balance, backup and export sees it with no special handling.
