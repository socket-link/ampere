# `macos-helper`: `ampere-helper`

The Swift process the CLI spawns to reach Apple's on-device model (AMPR-375).

`FoundationModels` is a Swift-only framework with no C or Objective-C headers, so a JVM cannot
call it through JNA, FFM or cinterop. A child process is the one route. This package is that
process. `ampere-cli/src/jvmMain/.../cli/ondevice/` is its JVM half: the supervisor, the
`LocalInferenceEngine` built on it, and the CLI's binding.

## What the CLI uses it for

Rung ZERO serves display text only (AMPR-372): words a person reads that nothing in a run acts
on, decides on or remembers. No agent call can reach the device (AMPR-459), so the engine is not
bound into the agents' transports. It backs one display-text path, `:ask <question>` in the TUI's
command mode, through `OnDeviceInferenceSession`. The session is on-device only. When the device
cannot serve, `:ask` says why and that nothing was sent.

The status bar shows the on-device indicator whenever a helper was found. Event rows for calls
that ran on this device carry the `◆` marker.

## The wire

Newline-delimited JSON, versioned, both ways. `Sources/AmpereHelper/Protocol.swift` is the Swift
half and `cli/ondevice/AmpereHelperWire.kt` the Kotlin half. They are kept in step by hand, which
is what `probe`'s version range exists to make safe.

| op | params | result |
|---|---|---|
| `probe` | — | `protocolMin`, `protocolMax`, `build`, `services` (`["inference"]`). Answered for any version. |
| `inference.probe` | — | `availability`, plus `contextSize` when it is `available`. Never prompts, never loads the model. |
| `inference.generate` | `prompt`; optional `instructions`, `maxOutputTokens`, `temperature` | `text`. One `LanguageModelSession` per request. |
| `inference.count` | `text`, or `prompt` with optional `instructions` | `tokens`, or `null` before macOS 26.4. |

The engine sends a request's parts apart, as the iOS engine passes them to the framework
(AMPR-462). The system message becomes the session's instructions and the user's turn the
prompt. The output budget goes to `GenerationOptions(maximumResponseTokens:)`, so it bounds what
the model writes, and the temperature is clamped to 0–1. A count of a request counts the
instructions and prompt entries a session starts from, the input the model bills.

Failures are `{"ok":false,"error":{"code","message"}}` with one of `unsupported_protocol`,
`unknown_op`, `malformed_request`, `platform_refused` or `generation_failed`.

The frame shape, the op names, the error codes and the availability tokens are those of Socket's
`socket-helper` (SCKT-853), and this helper serves every inference op that one does, so Socket's
engine runs unchanged against this binary. The reverse is not true. Socket's helper reads only
`prompt`, so this engine's requests would reach the model without their instructions and output
cap, and it has no `inference.count`, which this engine would read as "cannot count".

**The helper makes no decisions.** It reports `SystemLanguageModel`'s availability as a token. The
`LocalUnavailableReason` codes, the provider id and the model id are stamped in Kotlin, as the iOS
engine does it, so no reason string crosses the wire.

**FoundationModels needs macOS 26 on Apple silicon with Apple Intelligence on.** `Package.swift`
keeps `.macOS(.v13)`, and `#if canImport(FoundationModels)` with `#available` gates the model, so
an older Mac answers `inference.probe` with `osTooOld` instead of failing to launch.

## Build

```bash
swift build -c release --package-path ampere-cli/macos-helper   # seconds, no dependencies, offline
```

`:ampere-cli:buildMacHelper` does this, on a Mac only, up-to-date-checked against `Sources/` and
`Package.swift`. `:ampere-cli:installJvmDist`, and therefore `./ampere-cli/ampere --rebuild`, copies
the binary into the distribution's `bin/`, next to the `ampere` start script. Nothing else
depends on it. `assemble`, `jvmTest` and CI never run `swift build`, because it costs memory on a
shared development Mac.

The package needs a Swift 6 toolchain and the macOS 26.4 SDK or later, where `tokenCount(for:)`
first appears. `swift` honours `DEVELOPER_DIR`, so a Mac whose selected Command Line Tools cannot
build it can name an Xcode instead:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :ampere-cli:installDist
```

A build that fails is a warning, not a failed install. The CLI is installed without the helper,
which is the CLI as it was before the helper existed, and the next install tries again.

## Where the CLI looks

1. `-Dampere.helper=<path>` or `AMPERE_HELPER=<path>`. A path that points at nothing usable is
   reported as such, never skipped.
2. The installed distribution's `bin/ampere-helper`.
3. `ampere-cli/macos-helper/.build/release/ampere-helper` under the working directory.

No helper, or a host that is not a Mac: the CLI behaves exactly as it did before the helper
existed. The status bar has no on-device segment, and `:ask` says why there is nothing to ask.

## It cannot outlive its caller

1. **stdin EOF exits the process**, mid-generation or not. This is the binding guarantee, and it
   lives here because a JVM that is `SIGKILL`ed runs no `finally` and no shutdown hook. The pipe
   still closes.
2. The JVM spawns the helper through `ProcessGroups` and holds its `CancellationAddress`. Closing
   the engine, or a request missing its deadline, terminates the group: SIGTERM, a bounded wait,
   then SIGKILL.
3. A JVM shutdown hook covers an orderly exit nobody called `close()` on.

The address is not persisted. Persisting an address is for work that must be reaped after its
supervisor dies, and this work must not survive its supervisor at all.

## Requests, deadlines and restarts

The JVM sends one request at a time and the helper answers in order. Every request has a deadline:
10 s for a probe or a count, 60 s for a generation, the same cut Ampere puts on a phase call. A
helper that misses one is terminated, because a stalled generation still holds the model and nothing
queued behind it would ever be answered. The next request starts a fresh helper, and so does the
next request after a crash.

AMPR-372 measured why this matters more than the happy path. From a background command-line
process on the development Mac, 24 of 516 generations stalled for 60 s to 17 minutes while other
builds were running. A helper that is missing, crashed or too old reads as unavailable with a
reason (`apple_foundation_models_helper_unreachable`, `…_incompatible`), never as a failed call.

## Testing

`cli/ondevice/*Test.kt` drives the whole JVM half against a **fake helper script**
(`FakeAmpereHelper`). The suite covers the protocol, every availability token, the deadline, the
restart, the version check, serialisation and the process-group termination. Per the fidelity
rule, the suite **clears nothing about this binary or FoundationModels**. This package's own suite
covers the Swift half (AMPR-470):

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  swift test --package-path ampere-cli/macos-helper
```

It has two halves, both sending request lines through `AmpereHelper.handle(line:inference:)`, the
function `main` hands every line it reads:

- **`WireTests` needs no model** and passes with Apple Intelligence off. `probe` is answered at any
  version; every other op outside the range is refused with `unsupported_protocol`; an unknown op
  with `unknown_op`; a line that is not a request frame with `malformed_request` and an empty id; a
  generation without a prompt, and a count with neither `text` nor `prompt`, with
  `malformed_request`. It also pins the tables `FoundationModelsInference` reads a request with:
  the temperature clamped to 0–1, the cap sent only when it is a positive whole number, and a count
  of a `prompt` taken as a request ahead of any `text`.
- **`LiveModelTests` needs a model that generates.** `inference.probe` reports `available` with a
  context size, a request counts more than its flattened string, a 16-token cap returns text without
  an error, and a generation with instructions returns text. Before any of it,
  `OnDeviceModelGate` asks the framework for one word, as `ampere-ios`'s gate does (AMPR-373),
  because GitHub's hosted runners report the model `available` and then cannot generate. **Where
  that word does not come back the live cases skip, not fail**, and the skip says why: Apple
  Intelligence off, an ineligible Mac, a model not downloaded, an OS before macOS 26. The count case
  also skips before macOS 26.4, where the helper answers `{"tokens": null}`.

The suite needs the same toolchain as the build. CI does not run it: hosted runners cannot generate,
and the model-free half alone would lengthen the macOS job's critical path.
