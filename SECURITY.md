# Security

## Reporting a vulnerability

Use GitHub's [private vulnerability
reporting](https://github.com/gauthig/FlexAndroid/security/advisories/new) rather
than a public issue.

This is a hobby project with one maintainer, so please do not expect a
same-day response. Include what you did, what happened, and what an attacker gains.

## What this app is trusted with

Worth being explicit about, because it is more than a typical phone app:

- **Your FlexRadio account password**, momentarily. The SmartLink tenant accepts
  Auth0's resource-owner password grant, which means the password passes through
  this process to reach the token endpoint. It is **never written to storage** — it is
  exchanged for tokens and dropped, and the in-memory copy is cleared from UI state
  on success.
- **A refresh token**, at rest. Encrypted with AES-256-GCM under a key generated in
  and never leaving the Android keystore. Excluded from cloud backup and device
  transfer via `data_extraction_rules.xml`.
- **Your transmitter.** The app can key an amateur radio station. A bug that leaves a
  carrier on the air is a safety problem, not only a software one.

## Design choices that are security-relevant

### Certificate pinning ordering

FLEX radios present self-signed certificates, so CA validation has nothing to anchor
to. The app pins the first certificate it sees per host, and **checks the pin before
sending `wan validate`**. That ordering is the property that matters: an attacker
intercepting the path never receives an authenticated session, only a dropped one.

A mismatch prompts the operator with both fingerprints in full and requires them to
confirm they reset the radio, rather than silently trusting or silently failing.

### The Auth0 client ID in the source is not a secret

`SmartLinkClient` contains a hardcoded Auth0 domain and client ID. These are public
application identifiers — the same values every FlexRadio client presents. They
identify the application, not a user, and there is no accompanying client secret.
There are no secrets in this repository.

### PTT cannot latch

The PTT control is wired to raw pointer events with the unkey in a `finally`, so
losing the gesture for any reason — the finger sliding off, an incoming call, the
window losing focus — drops the carrier. A toggle that survived the app losing focus
would be a transmitter left keyed.

## Known limitations

Not vulnerabilities so much as things you should know before trusting it:

- **Nothing is verified against hardware.** Every claim about how the radio responds
  comes from documentation, not from observation.
- **UDP hole punching means accepting unsolicited datagrams** on the bound port for
  the session's duration. Packets are validated as VITA-49 with FlexRadio's OUI and
  routed by stream ID, and malformed datagrams are dropped — but the port is open.
- **Nothing on the wire is authenticated per-packet.** That is the protocol's design,
  not this app's choice. Anyone who can inject UDP to the right port with a plausible
  header can feed the panadapter or the audio decoder. The parsers are written to
  reject malformed input rather than to trust it, and are unit-tested for it.
- **No certificate transparency or revocation checking** for the SmartLink broker
  connection beyond what the Android platform does by default.
- **The keystore fallback is to not persist.** If the keystore cannot mint a key, the
  refresh token is not stored at all. It is never written in plaintext.

## Scope

In scope: credential handling, the TLS and pinning implementation, packet parsing,
anything that could leave the transmitter keyed, and anything that leaks the
operator's callsign, location or radio's address.

Out of scope: the SmartSDR protocol's own lack of per-packet authentication, and
FlexRadio's server infrastructure. Report those to FlexRadio.
