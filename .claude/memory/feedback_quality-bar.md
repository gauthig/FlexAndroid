---
name: quality-bar
description: The standards this repo holds — zero lint findings, no baseline, one-way layering
metadata:
  type: feedback
---

Three standards for FlexAndroid, all enforced in CI or by review:

- **Zero lint findings and zero Kotlin compiler warnings.** `warningsAsErrors = true`.
  There is no `lint-baseline.xml` and adding one is not the answer. When the initial
  build produced 21 lint errors, all 21 were fixed — including replacing the
  deprecated `androidx.security:security-crypto` with a direct Android keystore
  implementation.
- **The layering `ui → radio → net` is one-way.** Nothing in `net/` touches Android UI
  or radio semantics; nothing in `radio/` touches a Composable.
- **Every protocol fact carries provenance.** `docs/PROTOCOL.md` marks each as `[API]`
  (FlexRadio documents it) or `[WIRE]` (established from other implementations).

**Why:** the layering is what lets 45 unit tests cover the protocol on a plain JVM with
no emulator, which is the only real safety net on a project that has never talked to
hardware. A lint baseline would hide the next real problem as effectively as the
existing ones. An unmarked protocol claim gets trusted later as though it were verified.

**How to apply:** run `./gradlew testDebugUnitTest lintDebug assembleDebug` before
calling anything done. If a lint finding seems wrong, disable the specific check with
the reason written down in `app/build.gradle.kts` — as was done for `ObsoleteSdkInt` —
rather than baselining it. See [[toolchain]] for why that particular one is disabled.
