# The FlexRadio protocol, as FlexAndroid uses it

This is a working reference for the parts of the SmartSDR interface this app
depends on. It is not a substitute for [FlexRadio's own API
documentation](https://github.com/flexradio/smartsdr-api-docs/wiki) — go there for
the full command surface. What this file adds is the parts that are **not** in that
documentation (the VITA-49 packet classes, the payload encodings, the SmartLink
handshake) and the *ordering* constraints that only show up when you try to build a
client.

Sources are marked throughout:

- **[API]** — documented in FlexRadio's API wiki.
- **[WIRE]** — not documented; established from the behaviour of FlexRadio's own
  client library and from [AetherSDR](https://github.com/aethersdr/AetherSDR)'s
  documentation of the same packets.

---

## 1. Transports

| Purpose | Transport | Port |
|---|---|---|
| Control, local radio | TCP, plaintext | 4992 **[API]** |
| Control, SmartLink | TCP, TLS | radio's public TLS port **[WIRE]** |
| Streaming data, both directions | UDP | 4991 from the radio; client's own port inbound **[API]** |
| SmartLink broker | TCP, TLS | `smartlink.flexradio.com:443` **[WIRE]** |
| Auth0 token endpoint | HTTPS | `frtest.auth0.com/oauth/token` **[WIRE]** |

## 2. Control protocol

Line-oriented ASCII. Terminator is LF, CR, or CRLF. **[API]**

### From the radio

```
V1.4.0.0                             protocol version, sent on connect
H6F4EC23D                            this client's 32-bit hex handle
R<seq>|<hex_code>|<message>[|<debug>] exactly one reply per command
S<handle>|<object> <k>=<v> ...        asynchronous object status
M<num>|<text>                         notice; bits 24-25 of num are severity
```

Two traps worth naming:

- **Handles and stream IDs exceed `Int.MAX_VALUE`.** `0x84000000` and `0x9F4EC23D`
  are ordinary values. Parse them as unsigned into a 64-bit type, or every later
  comparison silently misses. `FlexMessageParser.parseHex` returns `Long` for this
  reason.
- **Debug output can contain `|`.** Split the response into at most four parts and
  rejoin the tail, or a debug string with a pipe in it truncates.

### To the radio

```
C[D]<seq>|<command>
```

`seq` correlates the reply. The optional `D` requests verbose debug output.
`RadioConnection.send` suspends until the matching `R` line arrives; there is
exactly one, so request/reply is safe to model directly.

### Status object paths

The object path is the leading run of tokens containing no `=`. That single rule
handles every shape the radio emits:

```
S0|interlock state=TRANSMITTING           -> "interlock"
SA4E|slice 2 RF_frequency=1.44 mode=USB   -> "slice 2"
SA4E|display pan 0x40000000 center=1.3    -> "display pan 0x40000000"
```

**Meter status is the exception.** It separates pairs with `#`, not spaces, and
prefixes each key with the meter ID:

```
S0|meter 7.src=SLC#7.num=0#7.nam=LEVEL#7.unit=dBm#7.low=-150.0#7.hi=20.0
```

so it cannot go through the same field parser. `MeterRegistry.acceptDefinitions`
handles it separately. **[API]**

## 3. SmartLink handshake

Reaching a radio over the internet involves three parties. **[WIRE]**

```
  phone                    smartlink.flexradio.com              radio
    │                               │                             │
    │ POST /oauth/token             │                             │
    │  (Auth0 password-realm grant) │                             │
    │──────────────► frtest.auth0.com                             │
    │ ◄──── id_token, refresh_token │                             │
    │                               │                             │
    │ TLS connect :443              │                             │
    │──────────────────────────────►│                             │
    │ application register name=… platform=… token=<id_token>     │
    │──────────────────────────────►│                             │
    │ ◄── application info public_ip=…                             │
    │ ◄── radio list serial=… public_tls_port=… …                  │
    │                               │                             │
    │ application connect serial=… hole_punch_port=<local udp>     │
    │──────────────────────────────►│───── wake up ──────────────►│
    │ ◄── radio connect_ready handle=<wan handle> serial=…         │
    │                               │                             │
    │ TLS connect to radio's public IP:tls_port ──────────────────►│
    │ wan validate handle=<wan handle> ──────────────────────────►│
    │ ◄──────────────── V…, H… then the normal control protocol ──│
```

Then the session proceeds exactly as a LAN session does. `RadioConnection` is one
class for both transports because everything above the socket is identical.

### Port precedence

`radio list` may report two sets of ports. The precedence FlexRadio's own clients
use, and that `SmartLinkClient.parseRadioList` reproduces:

1. `public_tls_port` / `public_udp_port` — an operator's manual port forward. Wins.
2. `public_upnp_tls_port` / `public_upnp_udp_port`, if `upnp_supported=1`.
3. Neither: the radio needs **UDP hole punching**.

### Hole punching

With no forwarded ports, the client sends a handful of UDP datagrams to the radio's
public address at the same moment the broker tells the radio to send to ours. The
outbound packets create the NAT mapping that lets the radio's traffic back in. This
is what `VitaTransport.punchHole` does, and it is the mechanism that fails on
carrier-grade NAT — which is why the radio picker says so up front.

### Certificate pinning

FLEX radios present a **self-signed** certificate, so CA validation has nothing to
anchor to. FlexAndroid pins on first use and refuses to send `wan validate` if a
later certificate does not match. The ordering is the security property: an attacker
on the path never receives an authenticated session, only a dropped one. A factory
reset legitimately changes the certificate, so the operator is asked rather than
locked out. See `PinningTrustStore`.

## 4. Bring-up order

This sequence is not arbitrary. `RadioSession.configure` runs it in exactly this
order, and each constraint below is a real one:

```
client gui <client_id>       ← ONLY a GUI client may create a panadapter [API]
client station FlexAndroid
client program FlexAndroid
client udpport <local port>  ← MUST precede any stream create [API]
sub slice all / pan all / tx all / radio all / meter all / audio_stream all
meter list                   ← definitions must land before any meter packet
display pan create x=512 y=200
display pan set <id> center=… bandwidth=… min_dbm=… max_dbm=… fps=…
slice list                   ← adopt slice 0 if it exists; do not create a second
slice create freq=… mode=… pan=<id>   (only if slice 0 is absent)
slice set <n> active=1
stream create type=remote_audio_rx compression=none
remote_audio rx_on on
stream create type=remote_audio_tx
remote_audio tx_audio on
transmit set dax=0           ← else DAX wins as the transmit source
mic input pc                 ← points the radio's mic input at the network
slice set <n> tx=1
```

A stable `client_id` matters: v3 radios key per-client persistence off it, so
reusing one is what makes the radio restore your layout instead of treating every
launch as a new operator. `CredentialStore.clientId` generates one once.

`mic input pc` is **radio-wide state** that other clients can observe. It is set
once at session start rather than per-PTT, but it does change the radio for
everyone.

## 5. VITA-49 framing **[WIRE]**

Every streaming payload shares one 28-byte header.

```
word 0   header:  type(31:28) C(27) T(26) TSI(23:22) TSF(21:20) count(19:16) size(15:0)
word 1   stream id
word 2   class id high: 24-bit OUI — FlexRadio is 00-1C-2D
word 3   class id low:  information class (0x534C) << 16 | packet class code
word 4   integer timestamp
words 5-6 fractional timestamp
word 7+  payload
```

`size` is in 32-bit words and **includes the header**. The field that decides how to
read the payload is the **packet class code** in the low 16 bits of word 3.

Packets FlexAndroid emits use `type=0b0011` (extension data with stream ID),
`C=1`, `TSI=other`, `TSF=sample count` — matching what the radio's own firmware
sends.

### Packet classes

| PCC | Content | Payload |
|---|---|---|
| `0x8003` | Panadapter FFT | 12-byte sub-header, then uint16 bins |
| `0x8004` | Waterfall tile | 36-byte sub-header, then uint16 bins |
| `0x8002` | Meter values | N × (uint16 id, int16 raw) |
| `0x03E3` | Uncompressed audio | float32, interleaved stereo, 24 kHz |
| `0x0123` | DAX / reduced-bandwidth audio | int16, mono, 24 kHz |
| `0x8005` | Opus audio | one Opus frame, 24 kHz stereo |
| `0xFFFF` | Discovery beacon | ASCII `key=value` string |

Everything is **big-endian**, including the samples. Audio decoding is a byte-swap,
not a memcpy.

### FFT payload — the part that surprises people

The 12-byte sub-header:

| Offset | Type | Field |
|---|---|---|
| 0 | uint16 | `start_bin_index` |
| 2 | uint16 | `num_bins` |
| 4 | uint16 | `bin_size` (bytes per sample; 2) |
| 6 | uint16 | `total_bins_in_frame` |
| 8 | uint32 | `frame_index` |

Two things follow:

**Frames span packets.** A frame is complete only once received bins reach
`total_bins_in_frame`. Packets bearing a new `frame_index` mean the previous frame
was lost — the radio does not resend. `PanadapterStream` abandons the partial frame
rather than mixing the two.

**The samples are pixel rows, not power.** Despite being uint16, each sample is a Y
coordinate in the display the radio believes it is drawing: 0 is the top of the
window, `y_pixels - 1` the bottom. Recovering dBm needs three values from the
panadapter's own `display pan` status:

```
dBm = max_dbm − (sample / (y_pixels − 1)) × (max_dbm − min_dbm)
```

Read these as a 0–65535 range instead and you get a trace that looks plausible and
is entirely wrong. This is the single most important undocumented detail in the
whole protocol.

### Meter payload

`N × (uint16 meter_id, int16 raw_value)`, big-endian. The divisor depends on the
**unit** from the meter's definition, not on the meter:

| Unit | Divisor |
|---|---|
| dBm, dB, dBFS, SWR | 128 |
| Volts, Amps | 1024 |
| degF, degC | 64 |

Without the definition there is no divisor. `MeterRegistry.decode` skips unknown
IDs rather than guessing, because a wrongly scaled meter is worse than a missing one.

The S-meter is the meter named `LEVEL` with source `SLC` and `num` equal to the
slice number.

### Waterfall sub-header

Not used by this app, recorded for completeness. 36 bytes at offset 28:
`int64 FrameLowFreq`, `int64 BinBandwidth`, `uint32 LineDurationMS`, `uint16 Width`,
`uint16 Height`, `uint32 Timecode`, `uint32 AutoBlackLevel`,
`uint16 TotalBinsInFrame`, `uint16 FirstBinIndex`. Intensity is
`int16(raw) / 128.0` on an arbitrary positive scale — **not** dBm, and deliberately
decoupled from the spectrum's dBm range.

## 6. LAN discovery **[API]**

Since firmware v1.1.3, radios broadcast a beacon once per second to UDP 4991 as a
VITA-49 extension packet with class ID `0x534CFFFF`. The payload is an ASCII status
string:

```
model=FLEX-6600M serial=1234-… version=3.4.20.34 nickname=Back_Porch ip=… port=4992
```

Spaces in the name are encoded as underscores so the string stays parseable as
space-separated pairs, and the payload is NUL-padded to a 32-bit boundary. Both need
undoing — and NUL is not whitespace, so trimming does not remove it.

## 7. Transmit path

```
xmit 1                                    key   [API]
  → capture mic → float32 stereo 24 kHz
  → VITA-49 PCC 0x03E3 to the remote_audio_tx stream id
xmit 0                                    unkey [API]
```

Each `xmit` transition drives the interlock state machine, which reports back as
`S0|interlock state=…`. PTT is only honoured from `READY`; `NOT_READY` and
`TX_FAULT` are the radio refusing, with `reason=` saying why. **[API]**

Ordering on key-down is audio-stream-first so samples are already flowing when the
PA comes up. On key-up it is `xmit 0` first, so the carrier drops even if stopping
capture is slow.

## 8. Response codes seen in practice **[API]**

| Code | Meaning |
|---|---|
| `0x00000000` | Success |
| `0x50000005` | Wrong number or type of parameters |
| `0x5000000C` | Frequency out of range |
| `0x5000000D` | Invalid slice receiver |
| `0x50000016` | Malformed command |
| `0x5000002C` | Incorrect number of parameters |
| `0x5000002D` | Bad field |
| `0x50000032` | Unrecognised mode |
| `0x50000033` | Parameter out of range |
| `0x5000003D` | Transmit not supported by this radio |
| `0x50000042` | Not ready to transmit |
| `0x50000062` | Invalid client |

FlexAndroid additionally synthesises `0xFFFFFFFF` locally when a command outlives
its socket, so a caller waiting on a reply is never left suspended. That value is
not one the radio emits.
