---
concept: AgentSurface
status: stable
tracked_sources:
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/events/surface/**
  - ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/AgentSurfaceEvent.kt
  - docs/ampere/agent-surface.md
related: [EventSerialBus, PlugPermissions]
last_verified: 2026-04-29
---

# AgentSurface

## What it is

`AgentSurface` is a typed, serializable description of a UI render request
emitted by a plug (or an agent) when it needs human input or wants to
display a structured result. Variants are sealed: `Form` (multi-field
input), `Choice` (single/multi-select picker), `Confirmation` (accept /
reject with severity), `Card` (slot-based rich content). Every variant
carries a `correlationId` so the emitter can `await` the matching
`AgentSurfaceResponse` without coupling to bus internals.

Platform renderers — Compose Multiplatform on Desktop / Android, native UI
on iOS, possibly text on CLI — translate each variant into local UI. The
contract lives in `commonMain` and intentionally references no platform
or framework types.

## Why it exists

A plug running in commonMain code cannot know what the host platform is.
If the contract for "show the user a confirm dialog" leaked any Compose,
SwiftUI, or terminal-rendering reference, plugs would either need to
import it (impossible across platforms) or every platform would need a
parallel plug API (combinatorial). Instead, the plug describes *what
it wants the user to do* and the platform decides *how to render that*.

Three design pressures:

1. **Plugs must be platform-agnostic.** Treat the surface contract as
   the entire plug↔host UI vocabulary. New surface kinds require a
   commonMain change followed by per-platform renderer changes — never
   one without the other.
2. **The model can't draw UI.** An LLM can't render pixels, but it can
   choose which surface to emit and fill in the fields. Sealed variants
   constrain the choice to four well-understood shapes.
3. **Responses are paired, not pushed.** `correlationId` makes a request
   and its response a transactional unit. Plugs use
   `awaitSurfaceResponse(correlationId)` to suspend until the user replies.
   Without correlation, async UI events would race.

## Where it lives

- `agents/events/surface/AgentSurface.kt` — the sealed `AgentSurface` interface and four variants.
- `agents/events/surface/AgentSurfaceField.kt` — typed fields for `Form` (Text, Number, Choice, Date, …).
- `agents/events/surface/AgentSurfaceResponse.kt` — the response shape, also keyed by `correlationId`.
- `agents/events/surface/AgentSurfaceBusExt.kt` — `awaitSurfaceResponse` extension.
- `agents/domain/event/AgentSurfaceEvent.kt` — the bus event carrying a surface request.
- `docs/ampere/agent-surface.md` — design doc with renderer guidance.

## Invariants

- **No platform types in the contract.** `AgentSurface` and its variants reference only `kotlinx.serialization`, `commonMain` types, and primitives. A `Composable`, `UIView`, `View`, or terminal type appearing in this package is a violation.
- **`correlationId` pairs request and response.** Every variant requires one; every `AgentSurfaceResponse` carries the same id. Renderers must propagate it. Generating new ids on the response side defeats the pairing.
- **Variants are sealed.** New surface kinds extend the sealed hierarchy; plugs can't define their own. This is what makes per-platform renderers exhaustively switchable.
- **Surfaces are emitted via the bus, not direct method calls.** `AgentSurfaceEvent` carries the surface to the platform layer. A plug reaching directly into a renderer skips logging, replay, and trace.
- **Field constraints in `Form` are validated by the renderer, then again by the response handler.** Don't trust the response; validate twice.
- **`Card.Slot` is sealed.** New slot kinds add a sealed variant — they don't introduce a `Custom(any)` escape hatch.

## Common operations

- **Ask for a typed input** — emit `AgentSurface.Form(correlationId, title, fields = listOf(AgentSurfaceField.Text(...)))`. `awaitSurfaceResponse(correlationId)` returns the typed field map.
- **Ask the user to pick** — `AgentSurface.Choice(...)`; set `multiSelect = true` for multi-pick, otherwise it's single-select.
- **Confirm a destructive action** — `AgentSurface.Confirmation(... severity = Severity.Destructive)` so the renderer can style accordingly without leaking renderer types.
- **Display a structured result** — `AgentSurface.Card(title, slots = listOf(Slot.Heading(...), Slot.KeyValue(...)))`.
- **Add a new surface kind** — extend the sealed `AgentSurface` (and `Slot` for cards). Add a renderer for each platform; CI must show all renderers updated together.

## Anti-patterns

- **Emitting strings or markdown when a typed surface fits.** `"Please enter your email:"` printed to a log loses the structure that lets the platform render properly and lets the trace show *what the user was asked*.
- **Generating a new `correlationId` in the renderer.** Now the request and response don't pair, and `awaitSurfaceResponse` hangs forever.
- **Importing Compose / UIKit / terminal types into `agents/events/surface/`.** Even "for one helper". Once it's in commonMain, every platform inherits it (or fails to compile).
- **Using `AgentSurface.Card` slot escape hatches like `Body(text = "<html>...")` to embed arbitrary markup.** Slots are typed for a reason; HTML in `Body` defeats per-platform rendering.
- **Branching renderer behaviour on string-matched titles.** "If title contains 'destructive' show red." Use `Severity` on `Confirmation` instead — that's what it's for.
- **Skipping the bus: passing an `AgentSurface` directly to a renderer instance.** Bypasses `AgentSurfaceEvent`, so the trace can't show the user prompt and the recall layer can't see what was asked.
