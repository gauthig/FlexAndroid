---
name: flexandroid-scope
description: FlexAndroid's deliberate scope and its real verification status as of 2026-08-05
metadata:
  type: project
---

FlexAndroid controls a FlexRadio SDR from an Android phone over SmartLink. The scope
is **one slice (slice A), basic receive and transmit**, sized for a phone screen.
Deliberately absent: a second slice, the waterfall, CW keyer, digital modes, filter
and AGC controls, Opus compression, automatic reconnect.

As of **2026-08-05** the protocol implementation is complete, 45 unit tests pass,
lint is clean, and both debug and minified release APKs build. **Nothing has been
verified against physical radio hardware** — no live connection, no audio in or out,
no on-air transmission.

**Why:** the gap between "parses correctly" and "keys a transmitter correctly" is
where all the remaining risk sits, and overstating it would put someone on the air
with untested software.

**How to apply:** never describe a feature as working on hardware. The README has a
verification table that must stay honest; update it when hardware testing actually
happens, not before. When a hardware bug report arrives, treat the bring-up sequence
in [[wire-protocol-gotchas]] as the first suspect.
