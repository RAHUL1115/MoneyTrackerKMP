# Home-screen widgets redraw from database changes, not from save code

Widgets redraw a moment after any write to the database, whether it comes from a screen, the web dashboard, SMS detection or a restore, so no save path needs to remember to refresh them. A settings write counts only when a setting actually changed, and settings the app keeps for itself (such as when the inbox was last read) never count. Writes made with raw SQL instead of the generated queries are not seen. Nothing in the app writes that way today; anything that starts to must refresh the widgets itself.
