# FlexAndroid — notes for Claude Code

An Android client that controls a FlexRadio SDR remotely over SmartLink. Kotlin,
Jetpack Compose, GPL-3.0-or-later.

Read `.claude/memory/MEMORY.md` for accumulated project context, and
`docs/PROTOCOL.md` before touching anything that goes on the wire.

## Build and verify

Everything below assumes the wrapper. Do not invoke a system Gradle.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

- JDK **17**, Android SDK platform **37**, build-tools **37.0.0**.
- AGP **9.3.1**, Gradle **9.6.1**, Kotlin **2.4.10**, `minSdk` 26.
- **AGP 9 removed the `org.jetbrains.kotlin.android` plugin** — Kotlin support is
  built in. Adding it back breaks configuration. `kotlin.plugin.compose` is still
  needed and still applied.
- If the working copy sits in a cloud-synced folder, pass
  `-Pflexandroid.buildDir=<path outside it>`. OneDrive holds handles open and Gradle
  fails with `Unable to delete directory`.

## Non-negotiables

**Zero lint findings and zero Kotlin warnings.** `warningsAsErrors = true`, and there
is no `lint-baseline.xml` on purpose. Fix findings; do not baseline them.

**The layering is `ui → radio → net`.** Nothing in `net/` references Android UI or
radio semantics. Nothing in `radio/` references a Composable. That boundary is why 45
unit tests can cover the protocol on a plain JVM, and it is the first thing to
preserve when adding code.

**Handles and stream IDs are `Long`.** `0x84000000` and `0x9F4EC23D` are ordinary
values on this protocol. An `Int` sign-extends and every later comparison silently
misses.

**`docs/PROTOCOL.md` marks each fact `[API]` or `[WIRE]`.** `[API]` means FlexRadio
documents it; `[WIRE]` means it was established from other implementations. New
protocol facts get the same marking. A claim with no provenance will be trusted later
as if it were verified.

## Things that will bite

- **FFT bins are pixel rows, not power.** Despite being uint16 they are Y coordinates
  into a `y_pixels`-tall window. Read them as a 0–65535 range and you get a trace
  that looks plausible and is completely wrong. `PanadapterStream` has the conversion.
- **Meter status uses `#` between pairs, not spaces.** It cannot go through the normal
  field parser. `MeterRegistry.acceptDefinitions` handles it.
- **Bring-up order is load-bearing** — `client gui` before the panadapter,
  `client udpport` before any stream, hole punch before streaming, transmit setup
  before PTT is offered. It lives in `RadioSession.configure` and nowhere else. See
  `docs/PROTOCOL.md` §4.
- **`res/mipmap-anydpi-v26` must keep its `-v26`.** Lint's `ObsoleteSdkInt` says to
  drop it since `minSdk` is 26; doing so makes AAPT2 fail to resolve
  `@mipmap/ic_launcher` entirely. The check is disabled with that explanation.
- **`gradlew` must stay mode `100755` in the index.** Git on Windows does not track
  the executable bit, and committing it as `100644` makes CI die with exit code 126.
  Check with `git ls-files -s gradlew`; fix with `git update-index --chmod=+x gradlew`.
  Building on Windows cannot catch this because it uses `gradlew.bat`.
- **PTT must not latch.** It is a raw gesture with the unkey in a `finally` so any
  loss of the gesture drops the carrier. Do not turn it into a `Button` or a toggle.

## Verification status

The protocol layer is complete and unit-tested. **It has never talked to a radio.**
Do not describe anything as working on hardware, and keep the README's verification
table honest when the situation changes.
