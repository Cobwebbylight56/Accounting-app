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

# Tink, which backs EncryptedSharedPreferences, is annotated with ErrorProne
# annotations that exist only at compile time. R8 treats the dangling
# references as errors and fails the release build, so they are declared
# absent here. Nothing looks for them at runtime.
-dontwarn com.google.errorprone.annotations.**

# Tink also refers to the Conscrypt provider, which is optional on Android;
# it falls back to the platform provider when it is not present.
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**
