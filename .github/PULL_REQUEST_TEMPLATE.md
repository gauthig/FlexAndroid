# What this changes

<!-- One or two sentences. Link the issue if there is one. -->

# Why

<!-- The problem, not the diff. -->

# How it was verified

Tick what you actually did. An honest "not tested against hardware" is more useful
than a blank section — most of this project is in that position.

- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` passes locally
- [ ] Added or updated unit tests
- [ ] Tested against a real radio — model and firmware: <!-- … -->
- [ ] Tested transmitting into a dummy load
- [ ] Tested over SmartLink (not just on the LAN)
- [ ] Tested on a device where the microphone does not offer 24 kHz

# Protocol changes

<!-- If this changes what goes on the wire, say where the behaviour is documented.
     docs/PROTOCOL.md marks each fact [API] (in FlexRadio's documentation) or
     [WIRE] (established from other implementations). New facts need the same. -->

- [ ] No change to the wire protocol
- [ ] `docs/PROTOCOL.md` updated

# Notes for the reviewer

<!-- Anything you are unsure about, or deliberately left out. -->
