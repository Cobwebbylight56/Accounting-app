// Root build script.  Plugins are declared (but not applied) here so that the
// versions resolved from gradle/libs.versions.toml are shared by all modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// Version bumping.
//
// Android will not install an APK whose versionCode is the same as, or lower
// than, the one already on the phone -- it fails with a bare "App not
// installed".  These tasks make it impossible to forget: each one raises the
// version name AND the version code together, in one place.
//
//   ./gradlew bumpPatch     1.1.0 -> 1.1.1   a fix
//   ./gradlew bumpMinor     1.1.1 -> 1.2.0   a new feature
//   ./gradlew bumpMajor     1.2.0 -> 2.0.0   a big change
//
// Run one, then rebuild.  `./gradlew currentVersion` prints where you are.
// ---------------------------------------------------------------------------

fun versionFile(): File = rootProject.file("version.properties")

fun readVersion(): java.util.Properties = java.util.Properties().apply {
    versionFile().inputStream().use { load(it) }
}

/**
 * Rewrites version.properties in place, keeping the explanatory comments at the
 * top of the file: only the four value lines are replaced, so the file stays
 * readable rather than being regenerated as bare keys.
 */
fun writeVersion(major: Int, minor: Int, patch: Int, code: Int) {
    val file = versionFile()
    val updated = file.readLines().joinToString("\n") { line ->
        when {
            line.startsWith("VERSION_MAJOR=") -> "VERSION_MAJOR=$major"
            line.startsWith("VERSION_MINOR=") -> "VERSION_MINOR=$minor"
            line.startsWith("VERSION_PATCH=") -> "VERSION_PATCH=$patch"
            line.startsWith("VERSION_CODE=") -> "VERSION_CODE=$code"
            else -> line
        }
    }
    file.writeText(updated + "\n")
    println("Version is now $major.$minor.$patch (version code $code)")
}

fun bump(part: String) {
    val properties = readVersion()
    val major = properties.getProperty("VERSION_MAJOR").toInt()
    val minor = properties.getProperty("VERSION_MINOR").toInt()
    val patch = properties.getProperty("VERSION_PATCH").toInt()
    // The code only ever goes up, whichever part of the name changed.
    val code = properties.getProperty("VERSION_CODE").toInt() + 1
    when (part) {
        "major" -> writeVersion(major + 1, 0, 0, code)
        "minor" -> writeVersion(major, minor + 1, 0, code)
        else -> writeVersion(major, minor, patch + 1, code)
    }
}

tasks.register("bumpPatch") {
    group = "versioning"
    description = "Increases the patch version and the version code (1.1.0 -> 1.1.1)"
    doLast { bump("patch") }
}

tasks.register("bumpMinor") {
    group = "versioning"
    description = "Increases the minor version and the version code (1.1.1 -> 1.2.0)"
    doLast { bump("minor") }
}

tasks.register("bumpMajor") {
    group = "versioning"
    description = "Increases the major version and the version code (1.2.0 -> 2.0.0)"
    doLast { bump("major") }
}

tasks.register("currentVersion") {
    group = "versioning"
    description = "Prints the version that the next build will carry"
    doLast {
        val properties = readVersion()
        val name = listOf("VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH")
            .joinToString(".") { properties.getProperty(it) }
        println("Version name: $name")
        println("Version code: ${properties.getProperty("VERSION_CODE")}")
    }
}
