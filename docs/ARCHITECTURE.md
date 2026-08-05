# Architecture

## The shape of the thing

```
┌──────────────────────────────────────────────────────────────────┐
│ ui/            Compose screens + FlexViewModel                    │
│                one immutable RadioSessionState drives everything   │
└───────────────────────────────┬──────────────────────────────────┘
                                │ StateFlow / SharedFlow
┌───────────────────────────────▼──────────────────────────────────┐
│ radio/         what the bytes mean                                │
│   RadioSession      owns the connection and its bring-up order     │
│   PanadapterStream  FFT reassembly, pixel rows → dBm              │
│   MeterRegistry     definitions + per-unit scaling                │
│   Bands / Modes     the operator-facing vocabulary                │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│ net/           bytes on wires. No radio semantics, no Android UI  │
│   RadioConnection   control channel, TCP or TLS                   │
│   SmartLinkClient   Auth0 + broker session                        │
│   VitaTransport     one UDP socket, both directions               │
│   VitaPacket        VITA-49 framing                               │
│   PinningTrustStore TOFU certificate pinning                      │
└──────────────────────────────────────────────────────────────────┘
```

Dependencies point one way. `net` does not know what a band is; `radio` does not
know what a Composable is. That boundary is not decoration — it is why 45 unit
tests can cover the protocol on a plain JVM with no emulator, and it is where most
of the risk in this project lives.

## Decisions worth explaining

### One class for LAN and SmartLink

`RadioConnection` handles both. Above the socket the protocol is byte-identical; the
only differences are how the socket is obtained (plain TCP versus TLS) and a single
extra `wan validate handle=…` line at the start of a SmartLink session. Two classes
would have meant two copies of the response-correlation and status-dispatch logic,
which is the part most likely to grow bugs.

### The session owns the ordering

Bringing a radio up is a sequence with real constraints — `client gui` before the
panadapter, `client udpport` before any stream, hole punching before the streams
start, transmit setup before PTT is offered. Those constraints are documented in
[PROTOCOL.md §4](PROTOCOL.md#4-bring-up-order) and enforced in exactly one place,
`RadioSession.configure`. Spreading them across the UI would make them unenforceable.

### State is one immutable value

`RadioSessionState` is a single data class containing the slice, panadapter,
transmitter, meters and connection phase. The UI recomposes from it wholesale. The
alternative — a flow per field — permits states that cannot physically exist, like a
new band's frequency displayed against the previous band's panadapter edges. Here
that is unrepresentable.

The one exception is audio levels. `micPeakLevel` and `rxPeakLevel` are read by
polling at 60 ms rather than pushed through state, because they change at audio-block
rate and routing them through the state flow would recompose the entire screen dozens
of times a second to move one bar.

### Spectrum is a replaying SharedFlow of one

`spectrum` has `replay = 1` and drops the oldest on overflow. Frames arrive 15–30
times a second and only the newest matters; buffering them would add latency and
memory for no benefit. Replay means a recompose after a configuration change is
never blank.

### PTT is a raw gesture, not a click

The PTT control is a `Box` with `awaitEachGesture`, not a `Button`. A `Button`
consumes the press and reports a completed *click*, which is the wrong event: PTT
needs the down and up edges separately. More importantly, `waitForUpOrCancellation`
returns on cancellation too — a finger sliding off, an incoming call, the window
losing focus — and the unkey lives in a `finally` so all of those drop the carrier.

A latched toggle would be easier to build and would leave a carrier on the air when
the app lost focus. That is not an acceptable failure mode for a transmitter.

### The keystore, not Jetpack Security

`androidx.security:security-crypto` is deprecated as of 1.1.0 with no drop-in
successor. Rather than depend on an abandoned wrapper, `KeystoreCipher` does
AES-256-GCM against the Android keystore directly — about 100 lines for a job that
is genuinely small: encrypt one short string at rest.

The key is generated in the keystore and is unextractable, so on a device with
hardware-backed keys the refresh token cannot be recovered by copying the app's data
directory. A fresh 12-byte IV per encryption is taken from the cipher rather than
chosen by us, because IV reuse under GCM is catastrophic.

**The account password is never persisted.** It is exchanged for tokens and dropped.

### Certificate pinning happens before authentication

FLEX radios present self-signed certificates, so CA validation has nothing to anchor
to. `PinningTrustStore` records the first certificate seen per host and refuses to
proceed on a mismatch.

The ordering is the actual security property: the pin is checked **before**
`wan validate` is sent, so an attacker on the path never receives an authenticated
session — only a dropped one. A factory reset legitimately changes the certificate,
so a mismatch asks the operator (showing both fingerprints in full) rather than
locking them out.

## Audio

### Why uncompressed, for now

The radio offers three receive formats and accepts two for transmit. This release
uses **uncompressed float32 stereo at 24 kHz** (packet class `0x03E3`) in both
directions, costing roughly 1.5 Mbit/s each way.

Opus (`0x8005`) would cut that to 32–64 kbit/s and is the obvious right answer for
cellular. It is not here yet because of an asymmetry:

- **Decoding** Opus on Android is safe. `MediaCodec` has had a software Opus decoder
  since API 21. It does need a synthesised `OpusHead` identification header in
  `csd-0`, since FlexRadio sends raw frames with no Ogg container.
- **Encoding** is not safe. An Opus *encoder* in `MediaCodec` is not guaranteed
  across the Android device fleet, and there is no pure-JVM Opus encoder on Maven
  Central to fall back to. Bundling libopus through the NDK would work and would add
  a native toolchain to a project that currently needs none.

Shipping an Opus transmit path that silently fails on some phones would be worse
than shipping an honest bandwidth cost. The intended fix is: probe
`MediaCodecList` at runtime, use Opus when both directions are available, fall back
to uncompressed otherwise, and tell the operator which one is in use.

### Sample rate handling

The radio's rate is fixed at 24 kHz. Devices do not all offer it.

- **Playback** opens at 24 kHz when available, 48 kHz otherwise, duplicating each
  sample. Integer-ratio duplication introduces no pitch error — only image content
  well above anything the radio passed.
- **Capture** opens at 24 kHz when available, 48 kHz otherwise, dropping alternate
  samples after the hardware's own anti-alias filter. Voice is band-limited far below
  12 kHz, so this is adequate, and unlike a fractional resampler it accumulates no
  timing drift over a long transmission.

Capture uses `VOICE_COMMUNICATION`, which enables platform echo cancellation and
noise suppression. On a phone held near a speaker playing receive audio, that is the
difference between usable and not.

### Packet cadence

Transmit audio goes out in 128-stereo-frame packets — about 5.3 ms at 24 kHz, a
1024-byte payload. Small enough to stay well under any path MTU, short enough that
PTT latency is not audible.

## What runs on which thread

| Work | Thread |
|---|---|
| Control-protocol reads | `Dispatchers.IO`, one coroutine per connection |
| VITA-49 receive and dispatch | `Dispatchers.IO`, the UDP reader coroutine |
| Microphone capture and packetisation | `Dispatchers.IO`, the capture coroutine |
| Audio playback writes | the UDP reader thread, non-blocking |
| State updates | wherever they originate; `MutableStateFlow` is thread-safe |
| Composition | main |

Two rules follow from this, and both are load-bearing:

**`onVitaPacket` must not block.** It runs on the UDP reader thread, which also
carries spectrum and meters. Audio writes use `WRITE_NON_BLOCKING` precisely so that
a full audio sink drops a buffer instead of stalling the spectrum.

**Command sends are serialised on the socket's output stream**, so `send` and
`sendAndForget` are safe from any thread.

## Known weak points

Named because they are the places to look first when something misbehaves.

- **Nothing is verified against hardware.** See the verification table in the
  [README](../README.md#verification-status). Every protocol claim here comes from
  documentation and other implementations, not from a radio that keyed correctly.
- **`reserveUdpPort` races.** The port is bound and released to learn a free number,
  then told to the broker, then bound for real by the session. Another process can
  take it in between. It surfaces as a failed connect the operator can retry, but it
  is a race.
- **No reconnect logic.** A dropped session goes to `FAILED` and stays there. There is
  no backoff-and-retry, which a mobile app on a train genuinely wants.
- **`mic input pc` is radio-wide.** Setting it changes state other clients observe.
  It is set once at session start rather than per-PTT, but it does change the radio
  for everyone.
- **The GUI client slot.** Registering as a GUI client is required to create a
  panadapter, and on a v3 radio it consumes a licensed slot for the whole session.
- **Waterfall packets are received and dropped.** Creating a panadapter implicitly
  creates a waterfall stream, and the radio sends tiles this app ignores. That is
  wasted bandwidth on the uncompressed path.
