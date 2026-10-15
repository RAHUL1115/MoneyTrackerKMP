# R8 rules for the release build. Libraries ship their own consumer rules; these cover the rest.

# kotlinx-datetime references kotlinx-serialization, which this app doesn't use.
-dontwarn kotlinx.serialization.**

# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
