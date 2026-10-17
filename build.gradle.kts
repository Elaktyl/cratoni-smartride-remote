buildscript {
    dependencies {
        // AGP 9 provides built-in Kotlin with a runtime dependency on KGP 2.2.10.
        // Pin KGP explicitly so built-in Kotlin and the Compose compiler plugin use the same version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
