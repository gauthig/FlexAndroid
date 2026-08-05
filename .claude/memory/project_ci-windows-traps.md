---
name: ci-windows-traps
description: Why CI broke on the first push, and why a Windows-only build check cannot catch it
metadata:
  type: project
---

The first CI run failed with **exit code 126** — command found but not executable.
`gradlew` had been committed as mode `100644` because Git on Windows does not track
the executable bit. Fixed with `git update-index --chmod=+x gradlew`, so the index
mode is now `100755`, plus a `chmod +x ./gradlew` step in both workflows as a guard.

`.gitattributes` covers the neighbouring hazard: `* text=auto eol=lf` with
`*.bat text eol=crlf`. Without it a Windows clone commits CRLF and the Linux runner
cannot execute `gradlew` at all — the shebang picks up the `\r` and the shell reports
a bad interpreter.

**Why:** the local verification that was supposed to catch this — cloning fresh and
building — ran on Windows and therefore invoked `gradlew.bat`, which is never
affected by either problem. A green build on one OS is not evidence about a POSIX
file mode or a line ending. The Linux runner was the only thing that could see it.

**How to apply:** after any commit that touches `gradlew`, check
`git ls-files -s gradlew` shows `100755`. When verifying something platform-shaped,
ask what the check actually exercised rather than trusting that it passed — see
[[quality-bar]]. Related: [[toolchain]].
