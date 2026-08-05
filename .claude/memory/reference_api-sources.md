---
name: api-sources
description: Where FlexAndroid's protocol knowledge came from, and how to get more
metadata:
  type: reference
---

Two sources, and they cover different halves of the problem.

**FlexRadio's own API wiki** — <https://github.com/flexradio/smartsdr-api-docs/wiki>.
Covers the control protocol completely: the `V`/`H`/`R`/`S`/`M` line grammar, every
command (`slice`, `display pan`, `transmit`, `xmit`, `stream`, `sub`, `client`,
`mic`, `remote_audio`), response codes, interlock states, supported modes, and the
LAN discovery beacon. Clone the wiki rather than scraping it:

```bash
git clone --depth 1 https://github.com/flexradio/smartsdr-api-docs.wiki.git
```

**AetherSDR** — <https://github.com/aethersdr/AetherSDR>, GPL-3.0, Qt6/C++. Documents
what the wiki leaves out: the VITA-49 packet class codes, the audio payload
encodings, the FFT and waterfall sub-headers, meter scaling divisors, and the
SmartLink handshake. The most useful files are
`docs/architecture/vita49-format.md`, `docs/architecture/audio-pipeline.md`, and
`src/core/SmartLinkClient.{h,cpp}`. Fetch individual files from
`raw.githubusercontent.com`; the repo is ~200 MB.

**Why:** the split matters. Anything from the wiki is documented and stable; anything
from AetherSDR is an observation of behaviour that could change with firmware. That is
why `docs/PROTOCOL.md` marks every fact `[API]` or `[WIRE]`.

**How to apply:** for a new command, check the wiki first. For anything about what a
UDP payload *means*, the wiki will not have it — go to AetherSDR's docs. Credit and
mark the source when adding to `docs/PROTOCOL.md`. Related:
[[wire-protocol-gotchas]].
