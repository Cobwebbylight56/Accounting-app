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

# PDFBox, used to read PDF bank statements. It reaches for a handful of classes
# that do not exist on Android — AWT imaging, Java Beans, the full Bouncy
# Castle for encrypted files — and resolves them at runtime only when needed.
# R8 treats the dangling references as fatal, so they are declared absent.
-dontwarn com.tom_roush.harmony.awt.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.logging.**

# Font boxes and encodings are looked up by name from PDFBox's own assets.
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
