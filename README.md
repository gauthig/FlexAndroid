# FlexAndroid

Remote control of a [FlexRadio](https://www.flexradio.com/) software-defined radio
from an Android phone, over SmartLink.

FlexAndroid gives you one slice — slice A — and the controls that matter when the
radio is not in the room: the panadapter, band and mode, frequency, RF power, and
push-to-talk using the phone's microphone and speaker (or any headset attached to
it). It is deliberately not a pocket-sized SmartSDR. A phone screen has room for one
receiver done well, and that is what this is.

> **Status: pre-release.** The protocol implementation is complete and unit-tested,
> and the app builds and installs. It has **not yet been verified against physical
> radio hardware.** See [Verification status](#verification-status) before trusting
> it on the air.

## What it does

- **Connects over SmartLink** — signs in to your FlexRadio account, lists the radios
  on it, and brokers a direct TLS connection to the one you pick, including UDP hole
  punching for radios with no forwarded ports.
- **Connects over your LAN** — enter the radio's IP address and skip FlexRadio's
  servers entirely. Useful in the shack, and the only path if SmartLink is down.
- **Shows the panadapter** for slice A, with a real dBm axis, the slice marker and
  its passband. Tap to tune, drag to pan, pinch to zoom.
- **Receives audio** through the phone speaker or a wired or Bluetooth headset.
- **Transmits** from the phone microphone with press-and-hold PTT, plus a TUNE
  button for adjusting an antenna or amplifier.
- **Reads the meters** — S-meter on receive, forward power and SWR on transmit.
- **Bands and modes** — 160 m through 2 m; LSB, USB, CW, AM, FM, DIGL, DIGU.

## What it does not do

Named explicitly, because "basic receive and transmit" is a deliberate scope and not
a list of things nobody got to:

- No second slice, no diversity, no multiflex operating of two slices at once.
- No waterfall. The radio sends one and the app ignores it; a phone's vertical space
  is better spent on the spectrum.
- No CW keyer or CWX memories, no digital modes, no logging, no rig control for
  third-party software.
- No Opus audio compression yet, which matters for cellular data — see
  [Bandwidth](#bandwidth) below.
- No filter, AGC, noise-reduction or equaliser controls. The radio keeps whatever
  another client set.

## Screens

```
┌─────────────────────────────┐
│ Back Porch      DISCONNECT  │  radio identity + interlock state
│ SmartLink · 1234-…  READY   │
├─────────────────────────────┤
│                             │
│   panadapter (slice A)      │  tap = tune, drag = pan, pinch = zoom
│   dBm grid + passband       │
│                             │
├─────────────────────────────┤
│ S7  ▓▓▓▓▓▓░░░░░   -85 dBm   │  becomes W / SWR while transmitting
├─────────────────────────────┤
│ 14.250.000      1 kHz  ▼ ▲  │
│ [160m][80m][40m][20m]…      │
│ [LSB][USB][CW][AM][FM]…     │
│ Power ────●──────  25%      │
│ Volume ──────●───  70%      │
│ Mic   ─────●─────  50       │
├─────────────────────────────┤
│ [ TUNE ] [       PTT      ] │
└─────────────────────────────┘
```

## Requirements

- Android 8.0 (API 26) or newer.
- A FLEX-6000 or FLEX-8000 series radio running SmartSDR v2 or v3 firmware.
- A FlexRadio account with SmartLink enabled on the radio, for remote use.

FlexAndroid registers as a **GUI client**, because the radio only lets a GUI client
create a panadapter. On a v3 radio that consumes one of your licensed GUI client
slots for as long as the app is connected.

## Bandwidth

This release requests **uncompressed** audio in both directions:

| Direction | Format | Rate |
|---|---|---|
| Receive | float32 stereo, 24 kHz | ~1.5 Mbit/s down |
| Transmit | float32 stereo, 24 kHz | ~1.5 Mbit/s up |

That is fine on Wi-Fi and wasteful on a cellular connection. The radio also offers
Opus, which would cut this to roughly 32–64 kbit/s, but Android does not guarantee an
Opus *encoder* on every device, and shipping a path that silently fails to transmit
on some phones is worse than shipping an honest bandwidth cost.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#audio) explains the trade-off and what
adding Opus would involve.

## Building

```bash
./gradlew assembleDebug
```

Full instructions, including the toolchain versions this is pinned to and how to
produce a signed release build, are in [docs/BUILDING.md](docs/BUILDING.md).

## Verification status

Being precise about this matters more than looking finished.

| Layer | How far it is verified |
|---|---|
| Control protocol parsing (`V`/`H`/`R`/`S`/`M`) | Unit tested against the literal examples in FlexRadio's published API documentation. |
| VITA-49 framing | Unit tested, including round-trip, unsigned stream IDs, and malformed-datagram rejection. |
| Panadapter frame assembly and dBm conversion | Unit tested, including multi-packet frames and dropped-frame recovery. |
| Meter scaling | Unit tested per unit type. |
| SmartLink radio-list parsing and port precedence | Unit tested. |
| Compilation, lint, unit tests | Run in CI on every push. |
| **Live connection to a radio** | **Not yet done.** |
| **Audio in and out against hardware** | **Not yet done.** |
| **Transmitting on the air** | **Not yet done.** |

The command sequences the app sends are drawn from FlexRadio's published API
documentation and cross-checked against the behaviour of existing open-source
clients, but a protocol that parses correctly is not the same as a radio that keys
correctly. Treat the first on-air use as a test, with a dummy load.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

FlexAndroid is an independent project. It is not affiliated with, endorsed by, or
supported by FlexRadio Systems. "FlexRadio", "SmartSDR", "SmartLink" and "FLEX" are
trademarks of their respective owner and are used here only to say what this software
interoperates with.

## Acknowledgements

- FlexRadio Systems publish the [SmartSDR TCP/IP API
  documentation](https://github.com/flexradio/smartsdr-api-docs/wiki), which is the
  basis for the control protocol here.
- The [AetherSDR](https://github.com/aethersdr/AetherSDR) project (GPL-3.0)
  documents the VITA-49 packet classes and audio payload formats that the API
  documentation leaves to be read off the wire. That documentation shortened this
  work considerably.

## Safety and legal

Transmitting requires an amateur radio licence for the band and mode in use, and you
are responsible for what your station puts on the air — including when you key it
from somewhere else. Remote operation has extra rules in most jurisdictions, and this
app cannot know yours.

The band table in this app uses ITU Region 2 (US) allocations for display and
clamping only. **The radio enforces its own region's transmit limits and is the
authority; this app is not.**
