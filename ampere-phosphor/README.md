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
- `link.socket:phosphor-core` and `link.socket:phosphor-lumos` 0.9.0
  (atmosphere, glyph, look switch, runtime types)

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

Glyphs do NOT coalesce — every event with a non-null glyph is handed, in
arrival order, to the bridge's `glyphSink`, which by default queues it on the
renderer's `VoxelFrameBuilder`.

| Event                                                   | Glyph    |
| ------------------------------------------------------- | -------- |
| `TaskEvent.TaskCompleted`                               | CHECK    |
| `CognitiveEvent.EscalationFired`                        | QUESTION |
| `TaskEvent.TaskFailed`                                  | EXCLAIM  |
| `ToolEvent.ToolExecutionCompleted` with `success=false` | EXCLAIM  |
| `MemoryEvent.MilestoneReached`                          | STAR     |

## Driving the orb's body

The atmosphere is the orb's skin. Phosphor 0.9.0's `CognitiveLookSwitch`
drives its body: with `VoxelFrameBuilder.lookSwitch` set, the builder reads a
scene agent's `cognitivePhase`, `state` and `escalated` from each snapshot and
blends the orb into a look from a table.

The bridge keeps one scene agent for the whole bus, `sceneAgentId` (default
`"ampere"`), added to `runtime.agents` on `start()` if the host has not placed
one, and writes through to it:

| Event                            | Scene agent                                  |
| -------------------------------- | -------------------------------------------- |
| `CognitivePhaseEvent.PhaseEntered` | `cognitivePhase` ← the phase; `escalated` ← false |
| `CognitiveEvent.EscalationFired` | `escalated` ← true                           |
| `bridge.clearEscalation()`       | `escalated` ← false, ahead of the next phase |

The six PROPEL names map one to one onto Phosphor's; AMPERE has no `LOOP` or
`NONE`. The bus carries no end to an escalation, so the flag holds until the
next `PhaseEntered`, or until a host that knows the person answered calls
`clearEscalation()`. The bridge never sets the agent's activity `state`; a
run's lifecycle lives with the host.

Install `AmperePhosphorBridge.LOOK_SWITCH`, not Phosphor's default:

```kotlin
voxelFrameBuilder.lookSwitch = AmperePhosphorBridge.LOOK_SWITCH
```

It is Phosphor's confirmed phase → look table (searching lens, reasoning
walk, lighthouse beam, twin walkers, working ring, compacting fuse; the
waiting look while escalated) with every glyph dropped. Phosphor's default
table fires CHECK on `COMPLETE` and QUESTION on escalation itself, and the
bridge already fires both from the events, so installing the default next to
the bridge fires each glyph twice and `queueGlyph` restarts the fade: the glyph
blinks. Each glyph has exactly one owner, and that owner is the bridge.

A host that installs no switch sees no change: the builder ignores agents
while `lookSwitch` is null, so the atmosphere path and the glyphs are exactly
as before the agent existed.

## Wiring

```kotlin
val bridge = AmperePhosphorBridge(
    bus = eventSerialBus,
    runtime = cognitiveSceneRuntime,
    voxelFrameBuilder = voxelFrameBuilder,
)
bridge.start()
voxelFrameBuilder.lookSwitch = AmperePhosphorBridge.LOOK_SWITCH // optional: phase drives the body

// In your frame loop:
val snapshot = cognitiveSceneRuntime.update(deltaTimeSeconds)
bridge.onFrameTick()
val frame = voxelFrameBuilder.build(snapshot, deltaTimeSeconds)
// ...render frame...
```

The bridge does not own a frame loop. It mutates the runtime in response to
bus events. `onFrameTick` must be called once per frame so the bridge can
apply a coalesced atmosphere target when the in-flight transition completes.

### Redirecting glyphs

The builder's glyph queue only drains inside `VoxelFrameBuilder.build()`. A
consumer that never calls `build()` — one replaying a recorded trace rather
than rendering live frames — passes a `glyphSink` and overlays the glyph on
the replayed frame itself:

```kotlin
val bridge = AmperePhosphorBridge(
    bus = eventSerialBus,
    runtime = cognitiveSceneRuntime,
    voxelFrameBuilder = voxelFrameBuilder,
    glyphSink = { glyph, durationSeconds -> overlay.show(glyph, durationSeconds) },
)
```

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

- `start()` — register handlers on the bus and add the scene agent if absent.
  Idempotent while started.
- `stop()` — unsubscribe and quiesce. The scene agent stays in the runtime;
  the host that owns the scene decides when it goes. `EventSerialBus.unsubscribe(eventType)`
  removes every handler for the affected event types, not just the bridge's.
  Defer `stop` until other subscribers on the same types are also being torn
  down.

## What this module does NOT do

- Own a frame loop.
- Configure transition durations (the choreographer owns timing).
- Set the scene agent's activity `state` (SPAWNING / PROCESSING / COMPLETE).
  A run's lifecycle is the host's, for example Socket's `WorkflowStatus`.
- Keep one scene agent per run. One agent stands for the whole bus, as the
  atmosphere mapping already does.
- Bridge in the reverse direction (Lumos → AMPERE).
- Subscribe to `EscalationConsidered` or `ProvenanceCommitted` events —
  consumers wanting telemetry-grade or provenance signals provide their own
  subscriptions.
- Add CLI integration (`--with-lumos`, terminal layout, frame driving) —
  that's the consumer's job (see AMPR-170 for the CLI wiring).
