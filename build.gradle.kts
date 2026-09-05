// Root build script.  Plugins are declared (but not applied) here so that the
// versions resolved from gradle/libs.versions.toml are shared by all modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
