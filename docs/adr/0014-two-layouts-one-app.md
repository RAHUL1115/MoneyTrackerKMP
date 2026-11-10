# Two layouts share one app and one database

The app has a new layout and a classic layout, which arrange screens differently over the same data, settings and logic. Shared pieces (setting definitions, choice lists, read models) live once and both layouts use them. The new layout is where new design work goes first. The classic layout stays for people who prefer it, at lower priority, and the app never tells people it is going away. Changing layout restarts the app, because the layout is read once at start.
