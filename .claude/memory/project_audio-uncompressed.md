---
name: audio-uncompressed
description: Why FlexAndroid uses uncompressed audio despite the bandwidth cost, and what Opus needs
metadata:
  type: project
---

FlexAndroid requests **uncompressed float32 stereo 24 kHz** (VITA-49 packet class
`0x03E3`) for both receive and transmit — roughly 1.5 Mbit/s each way. The radio also
offers Opus (`0x8005`) at 32–64 kbit/s, which is obviously better on cellular.

The blocker is asymmetric: `MediaCodec` has had a software Opus **decoder** since API
21, but an Opus **encoder** is not guaranteed across the Android device fleet, and
there is no pure-JVM Opus encoder on Maven Central to fall back to (Concentus is not
published there). Bundling libopus via the NDK would work and would add a native
toolchain to a project that currently needs none.

**Why:** an Opus transmit path that silently fails on some phones is worse than an
honest bandwidth cost. A ham who cannot transmit and does not know why is worse off
than one paying for data.

**How to apply:** the intended shape is to probe `MediaCodecList` at runtime, use
Opus when both directions are available, fall back to uncompressed otherwise, and
show the operator which is in use. Note that decoding raw Opus through `MediaCodec`
needs a synthesised `OpusHead` header in `csd-0` — FlexRadio sends bare frames with no
Ogg container. Related: [[flexandroid-scope]].
