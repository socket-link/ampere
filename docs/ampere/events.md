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

## Probe verdicts

`ProbeEvent.VerdictReached` carries one Probe's judgement of one subject onto the
bus, so a decision is visible in the trace rather than only returned to whoever
asked. It is Ampere-owned and primitives-only: the subject itself never crosses
the boundary, because a consumer's Probe may judge a type Ampere cannot name.

| Field | Meaning |
| --- | --- |
| `probeId` | The Probe that reached the verdict. |
| `subjectId` | Caller-supplied identity of what was judged — a Probe's subject type is unconstrained, so the SPI cannot ask a subject for its own id. |
| `verdict` | `Holds`, `Warn`, `Violated`, or `Undetermined`, each carrying its `reason`. |
| `detail` | Free-form key/value for the Oscilloscope. Keep it small; it is stored in every trace that captures the event. |

Publishing is opt-in. A `ProbeSuite` constructed with an `eventBus` publishes one
event per report, in probe order, after every Probe in the suite has run:

```kotlin
val suite = ProbeSuite(
    probes = listOf(SequenceProbe()),
    eventBus = bus,
)

// Returns the reports as before, and puts each verdict on the bus.
val reports = suite.evaluate(subjectId = "plan-7", subject = workGraph)
```

Leave `eventBus` null — the default — and evaluation stays pure. Bench fixtures
and unit tests need no bus.

### Rendering a verdict in the Oscilloscope

A verdict event renders with its `subjectId`, not just its `probeId`. "Task T3
depends on T7, which is scheduled after it" is only legible if the row names T3;
a stream of `ampere.sequence` rows with no subject is a stream of unattributable
judgements.

The four verdicts render as **four** states, never as pass/fail with decoration:

| Verdict | Reads as | Rendering |
| --- | --- | --- |
| `Holds` | decided, good | Neutral. A clean pass needs no explanation and `reason` is often null. |
| `Warn` | decided, bad, not disqualifying | Signal Amber. Shows `reason`. |
| `Violated` | decided, disqualifying | Distinct from `Warn`, and never collapsed into it. Shows `reason`. |
| `Undetermined` | **not decided** | Visually distinct from `Holds`. Shows `reason` *and* `cause`, because "no published spec" and "the page needed a JS engine" lead to different next actions. |

`Undetermined` is the one that gets rendering wrong most easily. It is not a soft
pass and must never share a treatment with `Holds`: the Probe convicts but does
not acquit, and a viewer who reads an `Undetermined` row as "fine" has been told
the opposite of what happened. Routing on a verdict — re-plan, escalate to a
human — stays on the consumer side; the event is the signal, not the action.
