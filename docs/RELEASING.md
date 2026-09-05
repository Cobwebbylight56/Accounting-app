# Releasing, versioning, and getting the APK onto a phone

Three things go wrong with sideloaded Android apps, and all three look the same
from the outside — you install a new build and nothing changes. This is how the
project rules each of them out.

---

## Why an update "does nothing"

There are exactly three causes, and Android reports all of them as a bare
**"App not installed"** with no explanation.

| Cause | What happens | How this project prevents it |
|---|---|---|
| **The version code did not go up** | Android treats the APK as the same build or an older one and refuses it | `version.properties` plus `./gradlew bumpPatch`; the build server also adds its run number, so no two builds can share a code |
| **The signing key changed** | Android will not let a differently-signed APK replace an existing one — that is the whole point of signing | A fixed debug key is committed at `app/debug.keystore`, so builds from any machine or any CI run are signed identically |
| **It is a different app** | The debug build uses the id `com.rhys.financetracker.debug`, so it installs *alongside* the release build rather than over it | Install one or the other consistently; the release build is the one the workflow publishes |

If an update is ever refused, check those three in that order.

---

## Version numbers

Everything lives in **`version.properties`** at the project root:

```properties
VERSION_MAJOR=1
VERSION_MINOR=1
VERSION_PATCH=0
VERSION_CODE=2
```

- **`VERSION_MAJOR.MINOR.PATCH`** is what people see: "Version 1.1.0".
- **`VERSION_CODE`** is what Android compares. It must only ever go up, and a
  number must never be reused.

### Bumping it

```bash
./gradlew bumpPatch      # 1.1.0 -> 1.1.1   a fix
./gradlew bumpMinor      # 1.1.1 -> 1.2.0   a new feature
./gradlew bumpMajor      # 1.2.0 -> 2.0.0   a big change
./gradlew currentVersion # prints where you are
```

Each of those raises `VERSION_CODE` **as well as** the name, in one step, so it
cannot be forgotten. Commit the change with the work it describes.

### On the build server

The workflow sets `BUILD_NUMBER` to the run number, and `app/build.gradle.kts`
adds it to `VERSION_CODE`. So two builds of "1.1.0" from two different runs
still have different version codes, and the second will always install over the
first.

### Checking what is actually installed

**Settings → About** shows the version name, the version code and the date it
was built. Compare that with `./gradlew currentVersion` when something looks
stale.

---

## Getting the APK

### The permanent link

Once a version tag is pushed, the workflow publishes a release with the APK
attached at a URL that never changes:

```
https://github.com/Cobwebbylight56/Accounting-app/releases/latest/download/finance-tracker.apk
```

Bookmark that on the phone. It always resolves to the newest release, so
updating is: open the bookmark, tap the file, install over the top. Your data is
kept.

### Publishing a new version

```bash
./gradlew bumpMinor
git add version.properties
git commit -m "Version 1.2.0"
git tag v1.2.0
git push && git push --tags
```

The tag is what triggers the release. A few minutes later the link above serves
the new build.

### Every other build

Every push also builds an APK, but attaches it to the workflow run rather than
publishing it. To get one: **the repository on GitHub → Actions → the run →
Artifacts**. Useful for testing something before tagging it.

### Building it yourself

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/finance-tracker-1.1.0-release.apk
```

---

## Installing on the phone

1. Open the link, or copy the APK across.
2. Tap it. Android asks once for permission to install from that source —
   allow it.
3. Installing over an existing copy keeps all your data.

**Before a big upgrade, take a backup** (Settings → Backup and restore). It
takes seconds, and it means a bad build costs you nothing.

---

## Signing properly

The committed debug key is enough for personal use, and it is what makes updates
work reliably across machines. It protects nothing, though: anyone with the
repository could sign a build that Android would accept as an update.

For a real key, create one once:

```bash
keytool -genkeypair -v -keystore release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 -alias finance
```

**Keep that file and its password safe.** Losing it means you can never update
an installed copy again — you would have to uninstall (losing the data unless
you restore a backup) and start over.

Then either:

**Locally** — create `keystore.properties` in the project root; it is already
git-ignored:

```properties
storeFile=/full/path/to/release.jks
storePassword=…
keyAlias=finance
keyPassword=…
```

**On the build server** — add four repository secrets under
*Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `finance` |
| `KEY_PASSWORD` | the key password |

The workflow picks them up automatically. Without them it falls back to the
debug key, so a build always produces something installable rather than an
unsigned APK that fails with no explanation.

**Switching keys later breaks updates**, because the signature changes. If you
move from the debug key to a real one: take a backup, uninstall, install the
newly signed build, restore.
