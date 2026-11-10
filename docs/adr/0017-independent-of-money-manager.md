# Independent app that can read Money Manager backups

MoneyTracker is its own app, written from scratch. It can import and export Money Manager's `.mmbak` backup format so people can switch without losing their history. Code, text, images and icons are never copied from that app or any other; importers are written from the file format alone. Some data rules (refunds as negative expenses, the month start day, the settings the backup carries) match that format so that imports round-trip, and are recorded as decisions here so nobody "fixes" them.
