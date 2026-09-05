# Room / Hilt / Compose keep rules.  The AGP-supplied defaults cover most of
# this; the entries below protect the reflective surfaces we rely on.

# Entities are serialised to JSON backups by name.
-keepclassmembers class com.rhys.financetracker.data.local.entity.** { *; }
-keep class com.rhys.financetracker.data.local.entity.** { *; }

# Enum names are persisted in the database and in backups.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager workers are instantiated by name.
-keep class * extends androidx.work.ListenableWorker { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
