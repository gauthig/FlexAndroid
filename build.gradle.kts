plugins {
    // AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` is no
    // longer applied. The Compose compiler is still a separate Kotlin plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/*
 * Escape hatch for working copies inside a cloud-synced folder.
 *
 * OneDrive, Dropbox and iCloud Drive all hold handles open on files they are
 * uploading, and Gradle deletes and recreates its output directories constantly.
 * The collision shows up as "Unable to delete directory ...\build\..." partway
 * through an otherwise fine build.
 *
 * Passing -Pflexandroid.buildDir=<path> (or setting it in ~/.gradle/gradle.properties)
 * moves every module's outputs outside the synced tree. It changes nothing about
 * the build itself and is invisible to anyone who does not set it.
 */
val relocatedBuildRoot: String? = providers.gradleProperty("flexandroid.buildDir").orNull

if (relocatedBuildRoot != null) {
    val root = file(relocatedBuildRoot)
    allprojects {
        layout.buildDirectory.set(File(root, path.replace(':', '/').trim('/').ifEmpty { "root" }))
    }
}
