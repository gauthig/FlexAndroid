# Changelog

Notable changes to FlexAndroid. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- Opus audio, once the encoder availability problem has a real answer. See
  [docs/ARCHITECTURE.md § Audio](docs/ARCHITECTURE.md#audio).
- Automatic reconnect with backoff after a dropped session.
- Verification against physical radio hardware, which gates everything else.

## [0.1.0] — 2026-08-05

First release. Protocol implementation is complete and unit-tested; **nothing has
been verified against physical radio hardware.**

### Added

- **SmartLink remote connection** — Auth0 sign-in, broker session against
  `smartlink.flexradio.com`, per-radio TLS with a one-shot WAN handle, and UDP hole
  punching for radios with no forwarded ports. Port precedence follows manual
  forwards, then UPnP, then hole punching.
- **Direct LAN connection** by IP address, bypassing FlexRadio's servers entirely.
- **Panadapter for slice A** with a real dBm axis, slice marker and passband overlay.
  Tap to tune, drag to pan, pinch to zoom. Handles multi-packet FFT frames and
  recovers cleanly from dropped ones.
- **Receive audio** through the phone speaker or any attached wired or Bluetooth
  headset, at 24 kHz with a 48 kHz fallback for devices that will not open 24.
- **Transmit** from the phone microphone with press-and-hold PTT that unkeys on any
  loss of the gesture, plus a TUNE control for antenna and amplifier adjustment.
- **Bands 160 m to 2 m** and modes LSB, USB, CW, AM, FM, DIGL, DIGU, with a
  per-band calling frequency and default mode.
- **Meters** — S-meter in dBm and S-units while receiving; forward power in watts
  and SWR while transmitting.
- **RF power, microphone level and local playback volume** controls.
- **Interlock state** surfaced in the header, so a radio refusing to key says why.
- **Trust-on-first-use certificate pinning** for radio TLS sessions, checked before
  authenticating, with an explicit operator decision on mismatch.
- **Keystore-backed credential storage.** The refresh token is encrypted with
  AES-256-GCM under a non-exportable key; the account password is never persisted.
- **Foreground service** so audio and the radio keepalive survive the screen going
  off.
- 45 unit tests covering control-protocol parsing, VITA-49 framing, panadapter frame
  assembly and dBm conversion, meter scaling, SmartLink radio-list parsing, LAN
  discovery beacons, and the band and mode tables.
- CI on every push: unit tests, lint with warnings as errors, and both debug and
  minified release builds.
- [docs/PROTOCOL.md](docs/PROTOCOL.md), documenting the parts of the wire protocol
  that FlexRadio's API reference leaves out, with each fact marked as documented or
  as established from other implementations.

### Known gaps

Deliberate, and listed in the README: no second slice, no waterfall, no CW keyer, no
digital modes, no filter or AGC controls, no Opus compression, and no automatic
reconnect.

[Unreleased]: https://github.com/gauthig/FlexAndroid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gauthig/FlexAndroid/releases/tag/v0.1.0
