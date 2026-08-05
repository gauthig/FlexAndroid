---
name: toolchain
description: Pinned build versions and the three build traps that cost real time
metadata:
  type: project
---

Pinned: JDK 17, AGP 9.3.1, Gradle 9.6.1, Kotlin 2.2.21, `compileSdk`/`targetSdk` 37,
build-tools 37.0.0, `minSdk` 26. Versions live in `gradle/libs.versions.toml` and
`gradle/wrapper/gradle-wrapper.properties`.

Three traps, all hit during the initial build:

1. **AGP 9 removed `org.jetbrains.kotlin.android`.** Kotlin support is built in;
   applying that plugin fails configuration with a pointed error message.
   `kotlin.plugin.compose` is still separate and still required.
2. **The current AndroidX releases require AGP 9.1+ and compileSdk 37.** Pinning
   AGP 8.13 forces every AndroidX dependency backwards, which is why the project
   moved forward instead.
3. **`res/mipmap-anydpi-v26` must keep its `-v26` qualifier.** Lint's
   `ObsoleteSdkInt` says to drop it because `minSdk` is already 26 — doing so makes
   AAPT2 fail with `resource mipmap/ic_launcher not found`. The check is disabled in
   `app/build.gradle.kts` with that reason written down.

**Why:** each of these presents as an unrelated error message, and two of them look
like the fix is to follow the tool's own advice.

**How to apply:** build with `./gradlew`, never a system Gradle. On a cloud-synced
working copy pass `-Pflexandroid.buildDir=<path outside the synced tree>`; OneDrive
holds file handles open and Gradle fails with `Unable to delete directory`. Related:
[[quality-bar]].
