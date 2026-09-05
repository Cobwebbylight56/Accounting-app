import java.io.File
import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// Version handling.
//
// Android refuses to install an APK whose versionCode is the same as, or lower
// than, the copy already on the phone.  It reports only "App not installed",
// which is why a rebuild can appear to change nothing.  Everything below exists
// to guarantee the number always goes up.
//
// The figures live in version.properties at the project root; bump them with
// ./gradlew bumpPatch (or bumpMinor / bumpMajor).
// ---------------------------------------------------------------------------
val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    require(file.exists()) { "version.properties is missing from the project root" }
    file.inputStream().use { load(it) }
}

/**
 * On a build server the run number is added, so every build it produces is
 * higher than the last even when the version name has not changed.  That makes
 * "I rebuilt it and the phone kept the old one" impossible.
 */
val appVersionCode: Int = run {
    val base = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()
        ?: error("VERSION_CODE is missing from version.properties, or is not a whole number")
    base + (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0)
}

/** "1.2.3", or "1.2.3 (build 47)" when a build server produced it. */
val appVersionName: String = run {
    val name = listOf("VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH")
        .joinToString(".") { versionProperties.getProperty(it) ?: "0" }
    val buildNumber = System.getenv("BUILD_NUMBER")
    if (buildNumber.isNullOrBlank()) name else "$name (build $buildNumber)"
}

android {
    namespace = "com.rhys.financetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rhys.financetracker"
        // Android 10 and above, as required by the specification.
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // Shown in Settings -> About, so what is on the phone can be checked
        // against what was built without guessing.
        buildConfigField("String", "BUILD_TIME", "\"${LocalDate.now()}\"")
        buildConfigField("int", "VERSION_CODE_VALUE", "$appVersionCode")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Room exports its schema so that every migration can be reviewed and
        // tested.  See docs/DATABASE.md.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        // A fixed debug key, committed to the repository on purpose.
        //
        // Android will not install an update signed by a different key than the
        // copy already on the phone -- it fails with "App not installed".  The
        // keystore Android generates locally is different on every machine and
        // on every build-server run, so builds from two places could never
        // update each other.  Pinning one debug key removes that whole class of
        // problem.  It is a debug key only: it protects nothing, and the
        // password is the Android default.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // The real key, supplied by keystore.properties or by the build
        // server's secrets.  See docs/RELEASING.md.
        create("release") {
            val keystoreProperties = Properties().apply {
                val file = rootProject.file("keystore.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            val storePath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_PATH")
            if (storePath != null && File(storePath).exists()) {
                storeFile = File(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key when no real one is configured, so a
            // release build always produces something installable rather than
            // an unsigned APK that fails with no explanation.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose — versions come from the BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Security & file access
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    // Dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
