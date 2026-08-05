---
name: wire-protocol-gotchas
description: The four SmartSDR protocol details that are undocumented and silently wrong if guessed
metadata:
  type: reference
---

Four things about the FlexRadio wire protocol that are not in the API documentation
and that fail quietly rather than loudly. Full detail in `docs/PROTOCOL.md`.

1. **FFT bins are pixel rows, not power.** Despite being uint16 on the wire, each
   sample is a Y coordinate into the display the radio believes it is drawing: 0 is
   the top, `y_pixels - 1` the bottom. Recovering dBm needs `min_dbm`, `max_dbm` and
   `y_pixels` from the panadapter's own `display pan` status:
   `dBm = max_dbm − (sample / (y_pixels − 1)) × (max_dbm − min_dbm)`.
2. **Meter status uses `#` between key/value pairs**, not spaces, with each key
   prefixed by the meter ID (`7.src=SLC#7.nam=LEVEL#…`). It cannot go through the
   normal status field parser.
3. **Handles and stream IDs exceed `Int.MAX_VALUE`.** `0x84000000`, `0x9F4EC23D`.
   Parse as unsigned into `Long`.
4. **Bring-up order is load-bearing.** `client gui` before creating a panadapter
   (only a GUI client may), `client udpport` before any stream create, UDP hole punch
   before streams start, `mic input pc` + `remote_audio tx_audio on` + `slice tx=1`
   before PTT is offered. Encoded once in `RadioSession.configure`.

**Why:** every one of these produces plausible-looking wrong behaviour rather than an
error — a spectrum trace that is smooth but meaningless, meters that never populate,
stream routing that silently never matches, or a PTT that keys a dead carrier.

**How to apply:** when spectrum, meters, or transmit misbehave against real hardware,
check these four before anything else. Sources are in [[api-sources]].
