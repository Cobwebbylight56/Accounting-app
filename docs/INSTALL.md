# Installing Finance Tracker

There are two routes: build it yourself, or have someone build it and send you
the APK. The first is the one to use if you want to keep changing the app.

---

## Route 1 — build it yourself

### What you need

| Thing | Version | Where from |
|---|---|---|
| Android Studio | Ladybug (2024.2) or newer | <https://developer.android.com/studio> |
| Java Development Kit | 17 | Bundled with Android Studio |
| Android SDK | Platform 35, Build Tools 35.0.0 | Installed by Android Studio when prompted |
| A phone or emulator | Android 10 or newer | — |

Android Studio installs the SDK, the build tools and Gradle for you. You do not
need to install Gradle separately — the project includes a wrapper.

### Steps

1. **Open the project.** Android Studio → *File → Open* → choose the folder
   containing `settings.gradle.kts`.
2. **Let it sync.** The first sync downloads the dependencies and takes a few
   minutes. If a banner offers to install a missing SDK component, accept it.
3. **Connect a phone** with USB debugging turned on
   (*Settings → About phone → tap "Build number" seven times*, then
   *Settings → Developer options → USB debugging*), or start an emulator from
   *Device Manager*.
4. **Press Run** (the green triangle).

### From the command line

```bash
./gradlew assembleDebug      # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # builds and installs on the attached device
./gradlew test               # runs the unit tests
./gradlew lint               # runs Android Lint
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

### Building a release APK

A release build is smaller and faster, and is what you would install for
everyday use.

1. Create a signing key once:

   ```bash
   keytool -genkey -v -keystore finance-tracker.jks \
       -keyalg RSA -keysize 2048 -validity 10000 -alias finance
   ```

   Keep this file and its password safe. Losing it means you cannot update an
   installed copy of the app — you would have to uninstall and reinstall,
   which deletes the data unless you restore from a backup first.

2. Create `keystore.properties` in the project root (it is already in
   `.gitignore`, so it will not be committed):

   ```properties
   storeFile=/full/path/to/finance-tracker.jks
   storePassword=…
   keyAlias=finance
   keyPassword=…
   ```

3. Add the signing configuration to `app/build.gradle.kts`, above the
   `buildTypes` block:

   ```kotlin
   val keystoreProperties = java.util.Properties().apply {
       val file = rootProject.file("keystore.properties")
       if (file.exists()) file.inputStream().use { load(it) }
   }

   signingConfigs {
       create("release") {
           if (keystoreProperties.isNotEmpty()) {
               storeFile = file(keystoreProperties.getProperty("storeFile"))
               storePassword = keystoreProperties.getProperty("storePassword")
               keyAlias = keystoreProperties.getProperty("keyAlias")
               keyPassword = keystoreProperties.getProperty("keyPassword")
           }
       }
   }
   ```

   and inside `buildTypes { release { … } }` add:

   ```kotlin
   signingConfig = signingConfigs.getByName("release")
   ```

4. Build it:

   ```bash
   ./gradlew assembleRelease
   ```

   The APK lands in `app/build/outputs/apk/release/`.

---

## Route 2 — install a prepared APK

1. Copy the `.apk` file to the phone (email, USB, cloud storage).
2. Open it with the phone's file manager.
3. Android will ask permission to install apps from that source. Allow it,
   then install.

The debug and release builds have different application ids
(`com.rhys.financetracker.debug` and `com.rhys.financetracker`), so both can be
installed side by side without one overwriting the other's data.

---

## First run

The app asks for nothing and works immediately. Two things are worth doing
straight away:

1. **Settings → Load the example household** — puts the original spreadsheet's
   figures in so you can see how everything fits together. You can clear it
   later with *Settings → Delete everything*.
2. **Settings → Lock and security** — set a PIN, then turn on fingerprint
   unlock.

Permissions are asked for only when they are needed:

- **Notifications** — the first time you turn a reminder on.
- **Fingerprint** — granted automatically by the system prompt.
- **Files** — never requested as a broad permission; the system file picker
  hands the app one file or folder at a time.

---

## Troubleshooting

**"SDK location not found"** — open the project in Android Studio once; it
writes `local.properties` with the SDK path. Or create it yourself:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

**Gradle sync fails behind a proxy or firewall** — Gradle needs to reach
`dl.google.com` and `repo.maven.apache.org` on the first build only.

**"Installation failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE"** — an existing copy
was signed with a different key. Back up your data from inside the old app,
uninstall it, install the new one, then restore.

**Out of memory during the build** — raise the heap in `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4096m
```
