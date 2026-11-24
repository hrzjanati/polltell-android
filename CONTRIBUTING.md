# Contributing

Thanks for helping. The most useful contributions right now:

- **Compatibility reports**: which Android phone and version, which iPhone or iPad, what works and what does not
  (especially call audio: run *Run call-audio self-test* during a call and include its result). Open an issue.
- Bug reports with the link logs (see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md#what-to-include-in-a-report)).
- Fixes and features through pull requests.

## Your first contribution

1. Look for issues labelled [good first issue](https://github.com/amirwmr/tether/labels/good%20first%20issue) or
   [help wanted](https://github.com/amirwmr/tether/labels/help%20wanted), or ask in
   [Discussions](https://github.com/amirwmr/tether/discussions) before a bigger change.
2. Fork, clone and build: [docs/BUILD.md](docs/BUILD.md). You do not need the phones for most changes; the automatic
   tests run against fakes and fixtures.
3. Make the change on a branch, run the checks below, and open a pull request; the template has a short checklist.

Docs, translations (English / Persian) and device reports are as welcome as code.

## Repository layout

| Path | What |
| --- | --- |
| `android/app` | The Android app on the phone with the SIMs (link, Telecom, data sync, audio bridge, UI) |
| `android/core` | Shared Kotlin core: crypto, records, messages, codecs (the Kotlin SDK) |
| `android/client` | The Android client app |
| `android/helper` | The shell helper (call audio, network switches) |
| `ios/Tether` | The iPhone / iPad app (SwiftUI, CallKit) |
| `ios/TetherCore` | Swift package: protocol, crypto, link (the Swift SDK) |
| `protocol/` | `PROTOCOL.md`, test vectors and message fixtures |
| `tools/` | Test runners, debug hooks, install scripts |
| `docs/` | User and developer documentation (also the documentation site) |

## Before you open a pull request

1. Run every automatic check ([docs/TESTING.md](docs/TESTING.md)); CI runs the same on your pull request:
   ```bash
   tools/test-all.sh
   ```
   With the Android phone attached, also `tools/device-test.sh` (test line only, no real calls).
2. A change to the wire format updates `protocol/PROTOCOL.md`, both implementations and, for crypto or framing,
   the vectors and the message fixtures in `protocol/fixtures/` (`swift run tether-vectors` in `ios/TetherCore`, then copy `vectors.json` to `protocol/` and to the
   TetherCore tests).
3. A new message type: a `T` constant in `android/core/.../core/Messages.kt`, its handler in `Hub.onMessage`, the
   `MsgType` and body in `TetherCore/Messages.swift`, handling in `AppModel.received` and, for the Android client,
   in `ClientState.received` / `ClientCore.message`, a row in PROTOCOL.md §5, a fixture in `protocol/fixtures/`, and tests on every side.
4. Keep the link free of Bluetooth security (`LinkSecurityTest` fails otherwise) and test hooks dry (they may only
   touch the self-managed test line and the numbers `+10000000001…9`).
5. Never commit personal data: phone numbers, device ids, team ids, keys or logs from your phones. Local values go
   in the git-ignored files listed in BUILD.md; `tools/check-private.sh` checks your change against them.
6. User-facing text comes in English and Persian (`ios/Tether/Resources/*.lproj`, `android/app/src/main/res/values*`,
   `android/client/src/main/res/values*`).

## Testing on devices without real calls

`tools/dbg.sh loopback_call` runs complete calls on the Android app's self-managed test line with an in-process
client (ring, answer, Opus both ways, keypad, hang up) and prints latencies. `tools/dbg.sh only none` keeps test
traffic away from a linked iPhone; `only off` restores it. Never test with real calls or texts to real numbers.

## Running the checks

```bash
tools/test-all.sh            # everything CI runs, plus the privacy scan (~3 min on a Mac)
tools/test-all.sh --quick    # Android unit tests and the Swift package only (about a minute)
tools/test-all.sh --no-ios   # without Xcode (Linux)
```

What each suite protects: [docs/TESTING.md](docs/TESTING.md).

## Releases

Maintainers cut releases; the steps, versioning and what gets published are in
[docs/RELEASING.md](docs/RELEASING.md). Add a line under *Unreleased* in [CHANGELOG.md](CHANGELOG.md) for every
user-visible change.

## Style

Kotlin and Swift as in the surrounding code; comments explain why, not what. Commits: one topic per commit, a
single subject line saying what changed and why, in the style of `git log`, for example
`Contacts: the Android phone keeps a number's custom label on edits that send none`. No trailers.

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
