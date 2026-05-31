# AMPERE Events

AMPERE exposes cognitive and coordination state changes as typed `Event` values on
`EventSerialBus`. Publishers should use `AgentEventApi` when they are emitting
from an agent-owned workflow so events are persisted and source attribution stays
consistent.

## Threshold-driven cognitive escalation

`CognitiveEvent.EscalationFired` is emitted when an agent's normalized
uncertainty value meets or exceeds the configured escalation threshold.

```kotlin
val evaluator = UncertaintyEscalationEvaluator(agentEventApi)

evaluator.evaluate(
    uncertaintyValue = 0.82,
    threshold = 0.70,
    prompt = "Which migration path should we use?",
    cognitivePhase = CognitivePhase.PLAN,
)
```

The event payload includes:

| Field | Meaning |
| --- | --- |
| `agentId` | Agent whose uncertainty was evaluated. |
| `uncertaintyValue` | Normalized uncertainty in `0.0..1.0`; `1.0` means maximum uncertainty. |
| `threshold` | Normalized configured trip point in `0.0..1.0`. |
| `prompt` | Human-readable question or context that triggered evaluation. |
| `cognitivePhase` | PROPEL phase active when the threshold fired, if known. |

Subscribe through the normal event bus:

```kotlin
agentEventApi.onEscalationFired { event, _ ->
    println(
        "Uncertainty ${event.uncertaintyValue} crossed " +
            "${event.threshold} for ${event.agentId}",
    )
}
```

`EscalationFired` is distinct from `MessageEvent.EscalationRequested`.
`EscalationRequested` is discretionary thread escalation: an agent or workflow
chooses to put a message thread into `WaitingForHuman`. `EscalationFired` is
threshold-driven cognitive telemetry: it fires because uncertainty crossed a
configured trip point. Consumers that need confidence-specific UI behavior should
subscribe to `EscalationFired` rather than treating all human escalations as
uncertainty events.

## Near-miss uncertainty telemetry

`CognitiveEvent.EscalationConsidered` is emitted on **every** uncertainty
evaluation — both threshold trips and near-misses. It carries the same
`agentId`, `uncertaintyValue`, `threshold`, and `cognitivePhase` as
`EscalationFired`, plus a `fired: Boolean` discriminator. When `fired = true`,
the matching `EscalationFired` is published immediately after this event.

> ⚠️ **High-volume warning.** Uncertainty may be evaluated on every LLM call or
> tool invocation, producing **thousands of events per agent run**. Subscribe
> only if you have a real use for the data — telemetry pipelines, calibration
> analysis, near-miss UI warnings. Action-oriented consumers should ignore this
> event and subscribe to `EscalationFired` instead. Urgency is `LOW` for the
> same reason.

The event payload includes:

| Field | Meaning |
| --- | --- |
| `agentId` | Agent whose uncertainty was evaluated. |
| `uncertaintyValue` | Normalized uncertainty in `0.0..1.0`. |
| `threshold` | Normalized configured trip point in `0.0..1.0`. |
| `fired` | `true` iff this evaluation also produced an `EscalationFired`. |
| `cognitivePhase` | PROPEL phase active when uncertainty was evaluated, if known. |

Subscribe through the normal event bus:

```kotlin
agentEventApi.onEscalationConsidered { event, _ ->
    if (!event.fired && event.uncertaintyValue > event.threshold * 0.9) {
        warn("Near-miss: ${event.uncertaintyValue} approaching ${event.threshold}")
    }
}
```

### Ordering between Considered and Fired

When the threshold trips, the evaluator publishes `EscalationConsidered` first
(with `fired = true`) and `EscalationFired` second. That order is guaranteed at
the publish site only — `EventSerialBus` dispatch is concurrent, so subscribers
on separate event types cannot rely on cross-event ordering at the handler
level. A consumer that needs both signals together should subscribe to
`EscalationConsidered` (which carries enough payload to act on its own) rather
than correlating the two events.
