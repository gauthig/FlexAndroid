---
name: reviewing-dependency-bumps
description: How to review an Actions or Kotlin bump here, because green CI is not sufficient evidence
metadata:
  type: feedback
---

Six Dependabot PRs were reviewed and merged on 2026-08-05 (Kotlin 2.2.21 → 2.4.10,
and every GitHub Action to a new major: `checkout` 7, `upload-artifact` 7,
`setup-android` 4, `setup-gradle`/`wrapper-validation` 6, `action-gh-release` 3).
Green CI alone did not settle any of them, for three separate reasons:

1. **An unknown input on a JS action is ignored, not an error.** A bump that dropped
   an input we rely on would pass CI while silently doing nothing. Check by fetching
   `action.yml` at the new tag from `raw.githubusercontent.com` and confirming each
   input we pass is still declared.
2. **`release.yml` is never exercised by CI.** Anything touching it has zero CI
   evidence by construction, so read the new version's input docs instead.
   `action-gh-release` v3 made `draft` semantics explicit; `draft: true` is the value
   that preserves "stay a draft".
3. **Kotlin compiler warnings do not fail the build.** `warningsAsErrors` covers lint,
   not the Kotlin compiler, so a bump can regress the zero-warning bar invisibly.
   Build the trial merge locally and count `^w: ` lines.

**Why:** each of these is a way for a bump to look verified while being unverified.
The Kotlin bump was the only substantive change and needed a real local build; the
action bumps needed input-compatibility checks that no test run performs.

**How to apply:** trial-merge onto `main` in a throwaway clone, build, then merge for
real. Merging locally with `--no-ff` and pushing does mark the PRs merged on GitHub —
useful when there is no API token to hand. Related: [[quality-bar]],
[[ci-windows-traps]].
