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
