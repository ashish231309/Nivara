// Top-level build file. Plugins are declared here (without applying them) so that the
// versions resolved from the version catalog are shared by every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
