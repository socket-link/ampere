# Android Rung ZERO backend — ML Kit's Prompt API behind `LocalInferenceEngine`

**Issue:** [#1124](https://github.com/socket-link/ampere/issues/1124) — AMPR-226 · A1, Android Rung ZERO backend, recon (STOP gate)
**Linear:** [AMPR-508](https://linear.app/miley/issue/AMPR-508), under [AMPR-226](https://linear.app/miley/issue/AMPR-226) ([#590](https://github.com/socket-link/ampere/issues/590))
**Author:** @miley
**Date:** 2026-10-30

---

## Executive Summary

`:ampere-relay-local-android` now exists: an Android library (`minSdk 26`) whose
`MlKitLocalInferenceEngine` binds ML Kit's Prompt API, which runs Gemini Nano through the
system's AICore service, to ampere-core's `LocalInferenceEngine`. An Android build can now
report `LocalCapacity` from the device's model, generate on it with the system message as a
real system instruction, and size a call with the model's own token count.
`:ampere-core` gained no dependency.

| Question | Answer | Status |
| -- | -- | -- |
| Can the seam be bound without Firebase? | Yes. `com.google.mlkit:genai-prompt` alone, no Firebase project, no `google-services.json`, no App Check | **verified** (compiles; the resolved tree carries ML Kit's own `firebase-encoders` and `firebase-components` libraries, but no Firebase AI artifact and nothing that reads a Firebase project) |
| Which ML Kit version? | **1.0.0-beta4**, not the beta2 AMPR-226 checked: beta2 has no system-instruction field, beta5 is built against Kotlin 2.4 | **verified** (class files and POMs of beta2–beta5) |
| Does ML Kit expose a token count for `tokenCount`? | **Yes:** `GenerativeModel.countTokens(GenerateContentRequest): CountTokensResponse.totalTokens`, since beta2 | **verified** (API). Whether it matches what the model is charged: **untested** |
| Is Nano's quality on Ampere's real prompts measured? | **No.** No Nano device was available to this run; emulators do not run AICore | **untested** — §3 is the protocol, ready to run |
| Does "display text only" hold on Nano? | Keep it as the Android rule until measured. Nothing found here argues for loosening it, and two structural facts (output ceiling, §2.3) argue it may need tightening | **inferred** |

The measured verdict this ticket was written for is still owed: it needs a Gemini Nano V3+
device (Pixel 10 or newer preferred) attached to this Mac. Everything else in the ticket is
done, and §3 says exactly what the device run does.

---

## 1. The module

### What it is

| File | What it does |
| -- | -- |
| `ampere-relay-local-android/build.gradle.kts` | KMP module, `androidTarget()` only, `minSdk 26`, `api(project(":ampere-core"))`, `implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")`, ktlint applied. No `mavenPublishing` block. |
| `MlKitLocalInferenceEngine.kt` | The engine. `probe()`, both `generate` forms, both `tokenCount` forms, `close()`. Structured generation keeps the interface's default (Prose only). |
| `NanoModel.kt` | An internal seam (`NanoModel`, `NanoRequest`, `NanoReply`, `NanoStatus`) and its one ML Kit implementation, `MlKitNanoModel`. ML Kit's request and response classes are final with unreachable constructors, so the engine's decisions are written against the seam and tested against a fake. |
| `NanoUnavailableReason.kt` | Reason codes for `LocalCapacity.reason`. |
| `MlKitLocalInferenceEngineTest.kt` | 17 unit tests over a fake model (`:ampere-relay-local-android:testDebugUnitTest`). |

ML Kit beta4 raises the module's `kotlin-stdlib` from 2.3.10 to 2.3.21, a patch release in the same language version; ampere-core's own classpath is unchanged.

### How ML Kit maps onto the seam

| `LocalInferenceEngine` | ML Kit (beta4) | Notes |
| -- | -- | -- |
| `probe()` → `available = true` | `checkStatus() == FeatureStatus.AVAILABLE` | `modelId = "gemini-nano-on-device"`, `maxContextTokens = 4000`, `providerId = AIProvider_OnDevice.id` |
| `probe()` → `available = false` | `DOWNLOADABLE`, `DOWNLOADING`, `UNAVAILABLE`, or `checkStatus()` throwing | Reasons `gemini_nano_not_downloaded`, `gemini_nano_downloading`, `gemini_nano_unavailable`, `gemini_nano_status_failed`. `probe()` never calls `download()`. |
| `generate(request).instructions` | `SystemInstruction(text)` when `isSystemPromptAvailable()`; otherwise folded into the prompt as `flattenedPrompt` | ML Kit documents system instructions for Nano V3 and later, and recommends keeping them under ~150 words |
| `generate(request).maxOutputTokens` | `GenerateContentRequest.Builder.maxOutputTokens` | A non-positive cap is sent as no cap, as Apple's engine does |
| `generate(request).temperature` | `GenerateContentRequest.Builder.temperature` (Float) | Clamped to 0..1, as Apple's engine does. ML Kit's own range is not documented. |
| reply | `candidates.first().text` | No candidate → `Result.failure`. `FinishReason.MAX_TOKENS` is a reply, not a failure. |
| failure | `GenAiException` (with `errorCode`, `retryDelay`) | Returned unchanged in `Result.failure`; never retried; cancellation propagates |
| `tokenCount(text)` | `countTokens(generateContentRequest(TextPart(text)))` | `null` if counting throws |
| `tokenCount(request)` | `countTokens(<the request generate would send>)` | One function builds both, so the count includes the system instruction, as ML Kit's guide asks |

### Egress

ML Kit's Prompt API has no cloud mode: it runs on the device or fails. The engine therefore
cannot fall back to a cloud path on its own, and doesn't try. The failure is surfaced, and
`CognitiveRelay` decides what happens next. This is the property AMPR-226 §4 chose ML Kit over
Firebase's hybrid modes for. **Verified** from the API: `GenerativeModel` takes no endpoint,
key or mode.

### Not wired, on purpose

- **No CI compile.** CI's `JVM Lint + Build` step names its compile tasks, and editing
  `.github/workflows/ci.yml` is outside this ticket's scope (and a workflow change only Miley
  makes). CI runs `ktlintCheck` over the module, which configures it, but nothing compiles it
  until a line `:ampere-relay-local-android:compileDebugKotlinAndroid` and a
  `testDebugUnitTest` matrix entry are added. `:ampere-bindings-android` is in the same
  state today.
- **No publication.** A `mavenPublishing` block requires the module in both `publish.yml`
  command lines (`verifyPublishWorkflowCoverage` fails the build otherwise), which is also a
  workflow edit. Socket gets this module from a release that is Phase 2's to arrange.
- **No routing descriptor.** Nothing routes to `gemini-nano-on-device` yet (§2.4).

---

## 2. Backend contract delta

What `LocalInferenceEngine` and its surroundings lack for Nano, found while binding it.

### 2.1 Token count — the AMPR-460 question

**Outcome: ML Kit exposes one.** `GenerativeModel.countTokens(GenerateContentRequest)`
returns `CountTokensResponse(totalTokens: Int)`, and has since beta2. There is also
`getTokenLimit(): Int`. The Android engine is sized by the model's count, not by
`PromptTokenEstimator`. **Verified** from the API.

Two things are not known, and the device run (§3) settles both:

| Unknown | Why it matters | How §3 measures it |
| -- | -- | -- |
| Does `countTokens` include any turn the system wraps around the call? | Apple's framework adds about 50 tokens that a string count misses (AMPR-462). `GenerateContentResponse` has **no usage report**, so ML Kit offers nothing to check a count against the way Apple's `Response.usage` did. | Bisect the input size at which `generateContent` throws `REQUEST_TOO_LARGE` (code 12), and compare it with `countTokens` at that size. |
| What does `getTokenLimit()` report — the input window, or input plus output? | The engine reports ML Kit's documented "under 4000 tokens" until this is known. | Read it on the device, next to the `REQUEST_TOO_LARGE` boundary. |

If `countTokens` were ever unavailable, the fallback is `PromptTokenEstimator`'s four
characters per token. AMPR-372 measured what that costs on digit-dense prompts: a log with
timestamps was 1.55× the estimate on Apple's tokenizer. Nano's tokenizer is not measured.

### 2.2 System instructions and the ML Kit version

beta2, the version AMPR-226's memo checked, has **no system-instruction field**
(`GenerateContentRequest.Builder` has `promptPrefix`, a prefix-cache key, but no
`SystemInstruction`). beta3 added `SystemInstruction`, `isSystemPromptAvailable()`,
`isThinkingModeAvailable()`, `isStructuredOutputFeatureAvailable()` and `enableThinking`.
beta5 depends on `kotlin-stdlib` 2.4.0 and `kotlinx-coroutines` 1.11.0, while this repo
compiles with Kotlin 2.3.10. **beta4** (`kotlin-stdlib` 2.3.21, the version ML Kit's get-started
page shows on 2026-10-30) is the newest one this repo can take. **Verified** from the POMs and
class files.

On a model older than V3, `isSystemPromptAvailable()` is false and the engine folds the
instructions into the prompt. The fold is the same layout every prompt-only engine is handed.

### 2.3 Output ceiling — the delta most likely to bite

ML Kit's guidance is to keep output under about 256 tokens, and long outputs (over 4K) "should
be avoided". Ampere's calls ask for far more:

| Caller | Output cap it sets | Source |
| -- | -- | -- |
| `OnDeviceInferenceSession.ask` (the one display-text path) | **2,048** (`DEFAULT_MAX_OUTPUT_TOKENS`) | `OnDeviceInferenceSession.kt:379` |
| PERCEIVE / PLAN / LEARN / tool arguments | 500–1,000 | AMPR-372 battery |
| tool-less reasoning steps, `callLLM`, `ModelBackedDecisionClient` | 4,000 (default) | AMPR-372 §6 |

On Apple's model, PERCEIVE, PLAN and LEARN answered in about 300 tokens of JSON. Whether ML
Kit **refuses** a cap above its ceiling, **truncates** at it, or **ignores** it is **untested**.
Each outcome changes the engine differently:

- If it refuses, the engine has to clamp the cap, and the relay has to know the clamp.
- If it truncates, JSON answers break mid-object. That is one more reason, on top of AMPR-372's
  content findings, to keep PROPEL calls off the device.
- If it ignores the cap, the window fit still reserves 2,048 of a 4,000-token window for
  output. That leaves the display-text path under 2,000 tokens of input on Android.

`LocalCapacity` has no field for an output ceiling, so the relay cannot know one. Phase 2
either adds one or has the Android host build `OnDeviceInferenceSession` with a smaller cap.
**Inferred** from the docs and the code.

### 2.4 Routing identity

- `AIModel_OnDevice` has one model, `AppleFoundationModels`, and its `makerId` is hard-coded
  `"apple-on-device"` for the whole sealed class (`AIModel_OnDevice.kt:35`). A Nano model needs
  its own maker id.
- The engine reports `modelId = "gemini-nano-on-device"`. The registry entry that routes to it
  must use that name, as Apple's engine reports `AIModel_OnDevice.AppleFoundationModels.name`.
- `LocalUnavailableReason`'s codes are Apple-named (`apple_intelligence_*`). Nano's codes live
  in the module until Phase 2 moves them to ampere-core, where a surface can word them.

### 2.5 Download, foreground and quota

- **Download is a verb the contract lacks.** Apple's model is managed by the system. Nano is
  `DOWNLOADABLE` until something calls `GenerativeModel.download()`. `probe()` must stay
  side-effect-free, so the download belongs to the host. Phase 2 decides whether that is a
  Socket concern or a method on the Android engine. **Verified** (API); the right owner is
  **inferred**.
- **Foreground only.** A background call fails with `BACKGROUND_USE_BLOCKED` (code 30).
  `probe()` cannot see this: it reports available, and the generation then fails. **Verified**
  that the code exists; the behaviour is **untested**.
- **Quota.** AICore enforces a per-app inference quota: `BUSY` (9, with a `retryDelay`) and
  `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27). Today these reach the relay as an opaque failure.
  A typed failure that tells transient from permanent is a Phase 2 option, not a requirement.

### 2.6 Structured output and thinking mode

- **Structured output** needs `@Generable` Kotlin classes compiled by `genai-schema-compiler`
  (KSP, alpha), and `GenerateTypedContentRequest`. There is no runtime schema like Apple's
  `DynamicGenerationSchema`, so `EmissionPayload` shapes would need Android-side mirrors.
  Under AMPR-372's rule only Prose reaches ZERO, and Prose needs no schema. **Recommendation:
  don't build the mirrors** unless a structured kind is ever ratified for ZERO.
- **Thinking mode** (`enableThinking`, gated by `isThinkingModeAvailable()`, Nano V4+) is a
  variant for the device run (§3.4), not a contract change. Its tokens count against the same
  output ceiling.

---

## 3. Measurement — protocol, not yet run

AMPR-372 §1 is the protocol. Deviations are listed in §3.3.

### 3.1 What the run does

1. **Hardware.** A Gemini Nano V3+ device (Pixel 10 or newer preferred) on USB, with AICore
   updated and the model downloaded. Record the build, `getBaseModelName()`, `getTokenLimit()`
   and the four `is*Available()` answers.
2. **Real prompts, recaptured at HEAD.** AMPR-372's captured prompts are from `c7bca53c`, and
   since then AMPR-462 has sent the system message apart from the prompt. The scratch JVM
   capture (`Ampr372CaptureTest`, in the archived AMPR-372 workspace context, never committed)
   is re-run at HEAD over a recording `LocalUpstreamLlmClient`. It records each
   `LocalGenerationRequest`, not a flattened string.
3. **The production engine path, exactly.** A scratch instrumentation test, never committed,
   in this module's `androidInstrumentedTest`, reads the recorded requests from its assets
   and calls `MlKitLocalInferenceEngine.generate(request)` and `tokenCount(request)` on each.
4. **Cases.** The 40-case battery three times (120 replies). The AMPR-422 goal-met corpus
   (288 cases) once, through `ModelBackedDecisionClient`'s prompt as shipped.
5. **Grading.** Parse with ports of `LLMResponseParser.cleanJsonResponse` and
   `ModelBackedDecisionClient.normaliseAnswer` (AMPR-372's `grade.py`). Content graded
   sound / weak / wrong against each case's known answer, by the same definitions.
6. **Tokenizer.** `tokenCount(request)` against the chars/4 estimate per call site, and the
   `REQUEST_TOO_LARGE` bisection from §2.1.
7. **Output ceiling.** One request per cap in {64, 256, 512, 2,048}, asking for a long count,
   to see whether ML Kit refuses, truncates or ignores each cap (§2.3).

No cloud model is called: the battery has no cloud baseline, as in AMPR-372, and the goal-met
corpus already has Sonnet 5's from AMPR-422. On-device runs cost nothing.

### 3.2 Results tables (to fill)

**By call site** (AMPR-372 §2's columns):

| Call site | Replies | Sound | Weak | Wrong | Unparsed | p50 latency | Calls > 60 s |
| -- | -- | -- | -- | -- | -- | -- | -- |
| PERCEIVE | 24 | — | — | — | — | — | — |
| PLAN | 18 | — | — | — | — | — | — |
| LEARN | 18 | — | — | — | — | — | — |
| EXECUTE, tool arguments | 18 | — | — | — | — | — | — |
| EXECUTE, reasoning step | 15 | — | — | — | — | — | — |
| Prose, on-device assistant prompt | 18 | — | — | — | — | — | — |
| Prose, `callLLM` shape | 9 | — | — | — | — | — | — |
| **Total** | **120** | — | — | — | — | | — |

**Goal-met** (AMPR-372 §4's columns):

| | On Nano | Apple's model (AMPR-372) | Sonnet 5 (AMPR-422) | The rule in production |
| -- | -- | -- | -- | -- |
| False met | — | 85 / 144 (59.0%) | 65 / 432 (15.0%) | 120 / 144 (83.3%) |
| False not met | — | 12 / 144 (8.3%) | 2 / 432 (0.5%) | 48 / 144 (33.3%) |
| Unparsed | — | 0 / 288 | 0 | — |
| Latency p50 / p95 | — | 1.3 s / 6.8 s | 2.0 s / 4.0 s | — |

### 3.3 Deviations from AMPR-372's method, decided in advance

| Deviation | Why |
| -- | -- |
| The system message reaches the model as a system instruction, not flattened | That is the production engine path on Android (§1); AMPR-372 ran Apple's engine as it then was, flattened |
| The output cap and temperature reach the model | Same reason: AMPR-462 passes them, and so does this engine |
| Prompts recaptured at HEAD | AMPR-462 changed what the client hands an engine |
| The goal-met corpus runs once, not once plus 54 cases twice more | The ticket asks for one pass; flip rate is a follow-up only if verdicts look unstable |

### 3.4 Variants, if time allows

The 15 hardest cases (AMPR-372's `variant-subset`), with `enableThinking = true` on a V4
device, and with temperature 0. AMPR-372 found neither variant moved Apple's verdict.

---

## 4. Verdict (provisional)

**Keep "display text only" as the Android rule, unmeasured.** Nothing found in this run
supports loosening it:

- Nano's documented constraints are at least as tight as Apple's: a 4,000-token input window,
  output best under ~256 tokens, single turn, no tools. **Verified** from ML Kit's docs.
- AMPR-372's failures were content failures (aggregation, judgment, LEARN), not format or
  window failures. A model in the same size class is not expected to do better without
  evidence. **Inferred.**
- The output ceiling (§2.3) may make it tighter. If ML Kit truncates or refuses at ~256
  tokens, even the display-text path needs a smaller cap on Android. **Inferred.**

The verdict becomes measured when §3 runs. If the device run shows Nano is sound on a kind of
call Apple's model was not, the rule's per-kind form (AMPR-372 §8.2) is where a second kind
would be ratified. That would be a decision for that record, not a change made here.

---

## Appendix

### A. ML Kit Prompt API versions

| Version | `SystemInstruction` | `countTokens` | `kotlin-stdlib` | `kotlinx-coroutines-core` | Fits this repo |
| -- | -- | -- | -- | -- | -- |
| 1.0.0-beta2 | no | yes | 1.8.0 | 1.7.3 | yes, without instructions |
| 1.0.0-beta3 | yes | yes | 2.3.21 | 1.7.3 | yes |
| **1.0.0-beta4** | yes | yes | 2.3.21 | 1.7.3 | **yes — used** |
| 1.0.0-beta5 | yes (+ function-calling tools, `Feature` list) | yes | 2.4.0 | 1.11.0 | not until Kotlin 2.4 |

All declare `minSdk 26` and the AICore bind permission in their manifests.

### B. `GenAiException` error codes the engine can surface

`REQUEST_PROCESSING_ERROR` 4 · `CANCELLED` 7 · `NOT_AVAILABLE` 8 · `BUSY` 9 ·
`RESPONSE_PROCESSING_ERROR` 11 · `REQUEST_TOO_LARGE` 12 · `RESPONSE_GENERATION_ERROR` 15 ·
`NOT_SUPPORTED` 16 · `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` 27 · `BACKGROUND_USE_BLOCKED` 30 ·
`NOT_ENOUGH_DISK_SPACE` 501 · `NEEDS_SYSTEM_UPDATE` 604 · `AICORE_INCOMPATIBLE` −101
(genai-common 1.0.0-beta4).

### C. Open questions, by status

| Question | Status |
| -- | -- |
| ML Kit has a token count API | verified |
| `countTokens` matches what the model is charged | untested (no usage report to compare against; §2.1 bisection) |
| What `getTokenLimit()` reports | untested |
| ML Kit's behaviour at a cap above ~256 (refuse / truncate / ignore) | untested |
| ML Kit's temperature range | untested (undocumented; clamped to 0..1) |
| Instructions as `SystemInstruction` change answers versus folded | untested |
| Nano's content quality on Ampere's prompts | untested |
| "Display text only" holds on Nano | inferred (provisional verdict, §4) |
| The module needs no Firebase project, `google-services.json` or App Check | verified (no Firebase AI artifact resolved; ML Kit brings its own `firebase-encoders`/`-components`) |
| `probe()` cannot see the foreground-only rule | verified (API); behaviour untested |
| Who owns the model download | inferred: the host |

### D. Sources

- `com.google.mlkit:genai-prompt` 1.0.0-beta2…beta5 and `genai-common` 1.0.0-beta3…beta5, class
  files and POMs from Google's Maven repository, 2026-10-30
- https://developers.google.com/ml-kit/genai/prompt/android/get-started (beta4 dependency line,
  "under 4000 tokens", the `FeatureStatus` values, per-app quota)
- https://developers.google.com/ml-kit/genai/prompt/android/system-instructions (Nano V3 and
  later; count the instructions; under ~150 words)
- [AMPR-226's memo](https://github.com/socket-link/ampere/issues/590), §4
- `docs/ampr-372-rung-zero-eligibility.md`

---

## Next Steps (Phase 2, filed from the verdict)

1. **Run §3 on a Nano V3+ device** and fill §3.2 and §2.1's unknowns; then re-rule §4.
2. **Output ceiling** (§2.3): clamp in the engine or carry a ceiling in `LocalCapacity`, and
   give the Android host's `OnDeviceInferenceSession` a cap that fits.
3. **Routing identity** (§2.4): an `AIModel_OnDevice` entry named `gemini-nano-on-device` with
   its own maker id, its descriptor at rung ZERO and availability-gated, and Nano's reason codes
   in `LocalUnavailableReason`.
4. **Download ownership** (§2.5): decide host or engine; if engine, a method beside `probe()`
   that keeps `probe()` side-effect-free.
5. **Wiring**: `mavenPublishing` plus both `publish.yml` lines, the `ci.yml` compile line and a
   `testDebugUnitTest` matrix entry (workflow edits, Miley's).
6. **A bench scenario** for the Android engine, once a routing descriptor exists.
7. **Not recommended:** `@Generable` mirrors of `EmissionPayload` (§2.6), unless a structured
   kind is ratified for ZERO.

---

*Recon record for AMPR-508 (issue #1124), under AMPR-226*
