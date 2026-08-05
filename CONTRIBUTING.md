# Contributing to FlexAndroid

## The most useful thing you can do right now

**Test it against a real radio and report what happens.** This project's protocol
implementation is complete and unit-tested but has never talked to hardware. A single
report — "connected, spectrum was correct, PTT keyed but the audio was silent" — is
worth more than a feature.

If you do that, please use a dummy load for the first transmit test.

## Getting set up

[docs/BUILDING.md](docs/BUILDING.md) covers the toolchain. Short version: JDK 17,
Android SDK platform 37, then `./gradlew assembleDebug`.

## Before you open a pull request

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

CI runs the same commands plus `assembleRelease`. Two expectations:

- **Zero lint findings.** `warningsAsErrors` is on. There is no `lint-baseline.xml`
  and there should not be: a baseline hides existing problems exactly as well as it
  hides new ones. If you think a finding is wrong, say so in the pull request.
- **Zero Kotlin compiler warnings.** Not enforced by the build, but a change that
  adds one will be asked to fix it.

## What the code looks like

Match what is already there. Concretely:

- **Four-space indent, 100-column soft limit.** `kotlin.code.style=official`.
- **Every file carries the GPL header.** Copy it from a neighbouring file.
- **KDoc on anything non-obvious**, and the doc says *why*, not *what*. The signature
  already says what.
- **Comments earn their place.** A comment that restates the next line is noise. A
  comment explaining why the ordering matters, or why the obvious approach is wrong,
  is the reason the next person does not break it. `RadioSession.setPtt` is the model
  to follow.
- **`Long`, not `Int`, for handles and stream IDs.** `0x84000000` is routine and
  sign extension silently breaks comparisons.

## The layering rule

```
ui → radio → net
```

This is not negotiable, because it is what makes the protocol testable without a
device:

- Nothing in `net/` may import from `radio/` or `ui/`, or reference Android UI types.
- Nothing in `radio/` may import from `ui/` or reference a Composable.
- Nothing in `net/` or `radio/` should need an emulator to test.

If a change wants to cross those lines, that is usually a sign the logic belongs on
the other side of the boundary.

## Tests

Unit tests go in `app/src/test`. The valuable ones are built from **literal examples
in FlexRadio's documentation** — see `FlexMessageParserTest` — so that a change
breaking protocol compatibility fails a test rather than a radio.

Worth testing:

- Anything that parses bytes or strings off the wire.
- Malformed input. The radio is not the only thing that can send a datagram to port
  4991.
- Boundary conditions in the frequency, dBm and meter conversions.

Not worth testing: Compose layout, which changes shape faster than a test can be
kept honest.

## Protocol changes

[docs/PROTOCOL.md](docs/PROTOCOL.md) marks every fact as either **[API]** —
documented by FlexRadio — or **[WIRE]** — established from other implementations'
behaviour. If you add or correct something on the wire, update that file and mark
your source the same way. A protocol claim with no provenance is a guess that will
be trusted later.

## Scope

The app is deliberately one slice, basic receive and transmit, sized for a phone
screen. The README lists what is intentionally absent and why. Proposals to widen
that are welcome, but the question they have to answer is what they displace — the
screen is full.

## Licence and provenance

By contributing you agree your work is distributed under **GPL-3.0-or-later**.

Do not paste code from a proprietary client, from a decompiler, or from anywhere whose
licence you have not checked. Protocol *facts* observed from a radio are fine and are
what this project runs on; someone else's *implementation* is not, unless its licence
is compatible and the source is credited.

## Reporting a security problem

See [SECURITY.md](SECURITY.md). Not in a public issue.

## Code of conduct

Be decent. [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) has the long version.
