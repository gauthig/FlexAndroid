# Building FlexAndroid

## What you need

| Tool | Version | Why this one |
|---|---|---|
| JDK | **17** | AGP 9 requires 17 or newer. Verified against Temurin 17.0.20. |
| Android SDK Platform | **37** | `compileSdk = 37`. Newer AndroidX artifacts require it. |
| Android SDK Build-Tools | **37.0.0** | Matches the platform. |
| Gradle | **9.6.1** | Supplied by the wrapper — do not install it separately. |
| Android Gradle Plugin | **9.3.1** | Pinned in `gradle/libs.versions.toml`. |
| Kotlin | **2.2.21** | Compose compiler plugin version follows it. |

`minSdk` is 26 (Android 8.0).

The whole toolchain is pinned in `gradle/libs.versions.toml` and
`gradle/wrapper/gradle-wrapper.properties`. Changing a version means changing it
there, not in a build file.

## Quick start

```bash
git clone https://github.com/gauthig/FlexAndroid.git
cd FlexAndroid
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Install it on a connected device:

```bash
./gradlew installDebug
```

## The checks CI runs

Run these before opening a pull request; CI runs exactly the same set.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Two things to know about the lint configuration:

- `warningsAsErrors = true`. A new warning fails the build. This is deliberate —
  the alternative is a warning count that only ever goes up.
- Kotlin compiler warnings are also expected to be zero. The build does not enforce
  that mechanically, but a pull request that adds one will be asked to fix it.

## Building without Android Studio

No IDE is required. If you are starting from nothing:

```bash
# 1. A JDK 17. Any distribution works.
#    Windows:  winget install Microsoft.OpenJDK.17
#    macOS:    brew install --cask temurin@17
#    Debian:   sudo apt install openjdk-17-jdk

# 2. Android command-line tools, unpacked so that sdkmanager sits at
#    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
export ANDROID_HOME="$HOME/android-sdk"

# 3. The platform and build tools
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Then `./gradlew assembleDebug` as above. Gradle finds the SDK through
`ANDROID_HOME`, or through a `local.properties` containing
`sdk.dir=/path/to/android-sdk`. That file is per-machine and is **not** committed.

## If your working copy is in OneDrive, Dropbox or iCloud Drive

Cloud sync clients hold file handles open while uploading, and Gradle deletes and
recreates its output directories constantly. The collision looks like this, partway
through an otherwise healthy build:

```
> Unable to delete directory '...\app\build\generated\updated_navigation_xml\debug'
```

Move the build outputs out of the synced tree:

```bash
./gradlew assembleDebug -Pflexandroid.buildDir=/path/outside/the/synced/folder
```

Or set it once, in `~/.gradle/gradle.properties`:

```properties
flexandroid.buildDir=C:\\builds\\flexandroid
```

This affects only where outputs are written. It is invisible to anyone who does not
set it, and CI does not.

## Release builds

`assembleRelease` produces an **unsigned** APK with R8 minification and resource
shrinking enabled:

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

For a signed build, see [PUBLISHING.md](PUBLISHING.md). No keystore is referenced by
the committed build files, so cloning and building can never accidentally sign with
someone else's key.

## Project layout

```
app/src/main/java/com/gauthig/flexandroid/
├── net/          transport and protocol: no Android UI, no radio semantics
│   ├── FlexMessage.kt        control-protocol lines, parsed
│   ├── RadioConnection.kt    the command channel (TCP and TLS)
│   ├── SmartLinkClient.kt    Auth0 + the SmartLink broker
│   ├── PinningTrustStore.kt  trust-on-first-use certificate pinning
│   ├── VitaPacket.kt         VITA-49 framing
│   ├── VitaTransport.kt      the UDP socket, both directions
│   └── LanDiscovery.kt       discovery beacons on the local network
├── radio/        radio semantics: what the bytes mean
│   ├── RadioSession.kt       owns a connection and its bring-up order
│   ├── RadioState.kt         the observable state, as one value
│   ├── PanadapterStream.kt   FFT frame assembly and dBm conversion
│   ├── MeterRegistry.kt      meter definitions and scaling
│   └── Bands.kt              bands, modes, tuning steps
├── audio/        AudioTrack out, AudioRecord in
├── data/         keystore-backed credential storage
├── ui/           Compose screens and the ViewModel
└── service/      foreground service that keeps a session alive
```

The dependency direction is one-way: `ui` → `radio` → `net`. Nothing in `net`
knows what a band is, and nothing in `radio` knows what a Composable is. That is
what makes the protocol layer unit-testable without an Android device — and why
45 unit tests can cover it.

## Running the tests

```bash
./gradlew testDebugUnitTest
```

These are plain JVM tests with no device or emulator needed, because everything they
cover is deliberately free of Android dependencies. Results land in
`app/build/reports/tests/testDebugUnitTest/index.html`.

Instrumented tests (`app/src/androidTest`) need a device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

## Troubleshooting

**`SDK location not found`** — set `ANDROID_HOME`, or create `local.properties` with
`sdk.dir=...`.

**`Dependency ... requires Android Gradle plugin 9.x or higher`** — you are building
with an older AGP than the one pinned. Use the wrapper (`./gradlew`), not a
system-installed Gradle.

**`Unable to delete directory`** — cloud sync. See the section above.

**`Failed to apply plugin 'org.jetbrains.kotlin.android'`** — that plugin was removed
in AGP 9, which has built-in Kotlin support. If you added it back, remove it again.

**Lint fails on a warning you think is wrong** — say so in the pull request rather
than adding it to a baseline. `lint-baseline.xml` is deliberately absent: a baseline
hides existing problems as effectively as it hides new ones.
