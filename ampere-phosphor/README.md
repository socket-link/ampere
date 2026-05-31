# `:ampere-phosphor`

Bridges AMPERE's `EventSerialBus` onto a Phosphor Lumos
[`CognitiveSceneRuntime`](https://github.com/socket-link/phosphor). Any
consumer that holds a runtime can wire `EventSerialBus →
AmperePhosphorBridge → CognitiveSceneRuntime` and get a visually rich
representation of agent cognition for free — no CLI, terminal, or renderer
code required.

The module lives outside `:ampere-cli` so non-CLI consumers (a future server,
Socket, work-process automation) can use it without dragging in
terminal-handling code.

## Coordinates

```
link.socket:ampere-phosphor:<version>
```

Depends on:
- `:ampere-core` (cognitive event types)
- `link.socket:phosphor-lumos` (atmosphere, glyph, runtime types)

## Canonical PROPEL mapping

`DefaultPropelStrategy` maps each canonical PROPEL phase to a Lumos
atmosphere preset:

| Phase     | Atmosphere | Notes                                       |
| --------- | ---------- | ------------------------------------------- |
| Perceive  | LISTENING  | Eager arrival, absorbing input              |
| Recall    | THINKING   | Indexing memory                             |
| Observe   | LISTENING  | Perception-adjacent state monitoring        |
| Plan      | THINKING   | Deliberation                                |
| Execute   | READY      | Confident action                            |
| Learn     | THINKING   | Reflection and integration                  |
| _(idle)_  | IDLE       | Default rest state (scene initial preset)   |

The alternation across the cycle (LISTENING / THINKING / LISTENING /
THINKING / READY / THINKING) is intentional: no two consecutive phases share
an atmosphere, so the orb visibly progresses.

`UNCERTAIN` is reserved for escalation by design. The bridge applies it
directly when a `CognitiveEvent.EscalationFired` arrives, bypassing the
strategy and any in-flight transition. Custom strategies must not return
`UNCERTAIN` from `atmosphereFor`.

## Glyph mapping

Glyphs do NOT coalesce — every event with a non-null glyph is queued in
arrival order on the renderer's `VoxelFrameBuilder`.

| Event                                                   | Glyph    |
| ------------------------------------------------------- | -------- |
| `TaskEvent.TaskCompleted`                               | CHECK    |
| `CognitiveEvent.EscalationFired`                        | QUESTION |
| `TaskEvent.TaskFailed`                                  | EXCLAIM  |
| `ToolEvent.ToolExecutionCompleted` with `success=false` | EXCLAIM  |
| `MemoryEvent.MilestoneReached`                          | STAR     |

## Wiring

```kotlin
val bridge = AmperePhosphorBridge(
    bus = eventSerialBus,
    runtime = cognitiveSceneRuntime,
    voxelFrameBuilder = voxelFrameBuilder,
)
bridge.start()

// In your frame loop:
val snapshot = cognitiveSceneRuntime.update(deltaTimeSeconds)
bridge.onFrameTick()
val frame = voxelFrameBuilder.build(snapshot, deltaTimeSeconds)
// ...render frame...
```

The bridge does not own a frame loop. It mutates the runtime in response to
bus events. `onFrameTick` must be called once per frame so the bridge can
apply a coalesced atmosphere target when the in-flight transition completes.

## Coalescing semantics

Atmosphere targets coalesce. When a phase event arrives while the
choreographer is mid-transition, the bridge replaces the pending target
rather than appending. When the in-flight transition completes (detected on
the next `onFrameTick`), the pending target is applied via
`runtime.setAtmosphere(...)`.

The pending slot is a single nullable `AtmosphereState?` guarded by a
`Mutex`. An `EscalationFired` event clears the pending slot and immediately
calls `setAtmosphere(UNCERTAIN)`, interrupting any transition in flight.

## Providing a custom strategy

Implement `PropelToAtmosphereStrategy`:

```kotlin
object MyStrategy : PropelToAtmosphereStrategy {
    override fun atmosphereFor(phase: CognitivePhase): AtmosphereState = when (phase) {
        CognitivePhase.PERCEIVE -> AtmospherePresets.READY  // domain-specific
        else -> DefaultPropelStrategy.atmosphereFor(phase)
    }

    override fun glyphFor(event: Event): LumosGlyph? = DefaultPropelStrategy.glyphFor(event)
}

val bridge = AmperePhosphorBridge(
    bus = eventSerialBus,
    runtime = cognitiveSceneRuntime,
    voxelFrameBuilder = voxelFrameBuilder,
    strategy = MyStrategy,
)
```

Strategies are pure functions of their inputs — no state, no I/O, no bridge
access. Wave 4 Socket integration ships its own strategy for the Arc
lifecycle.

## Lifecycle

- `start()` — register handlers on the bus. Idempotent while started.
- `stop()` — unsubscribe and quiesce. `EventSerialBus.unsubscribe(eventType)`
  removes every handler for the affected event types, not just the bridge's.
  Defer `stop` until other subscribers on the same types are also being torn
  down.

## What this module does NOT do

- Own a frame loop.
- Configure transition durations (the choreographer owns timing).
- Bridge in the reverse direction (Lumos → AMPERE).
- Subscribe to `EscalationConsidered` or `ProvenanceCommitted` events —
  consumers wanting telemetry-grade or provenance signals provide their own
  subscriptions.
- Add CLI integration (`--with-lumos`, terminal layout, frame driving) —
  that's the consumer's job (see AMPR-170 for the CLI wiring).
