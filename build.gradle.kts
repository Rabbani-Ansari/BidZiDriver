// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.google.gms.google.services) apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false // Use the latest version
    kotlin("plugin.serialization") version "2.1.0"
}
buildscript {
    dependencies {
        classpath("com.google.gms:google-services:4.4.4")
        // Inside dependencies of buildscript
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.9.5") // or latest

    }
}