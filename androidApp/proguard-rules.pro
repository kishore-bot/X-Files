# UniFFI and JNA resolve the native bridge classes and members at runtime.
-keep class com.xfiles.engine.ffi.** { *; }
-keep class dev.oxikt.** { *; }
-keep class com.sun.jna.** { *; }

# --- Crash reports -----------------------------------------------------------
# Keep line numbers so a crash report a user chooses to send can be traced back
# with this build's mapping file. File names become "SourceFile", so reports show
# no source layout, and class names stay obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Optional diagnostics backend is instantiated by manifest metadata via reflection (firebase build only).
-keep class com.xfiles.android.ConsentedCrashBackendFactory { public <init>(); public *; }
