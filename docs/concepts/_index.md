# AMPERE Concept Cells

Persistent, retrievable context that AMPERE coding agents inherit at session init.
These files are not a glossary — they encode invariants, rationale, and known
anti-patterns for AMPERE's load-bearing primitives. Read [`AGENTS.md`](../../AGENTS.md)
for the contract that ties this directory to agent behaviour and CI.

> **For agents:** before working on any of the files listed under
> `tracked_sources` in a concept, read that concept file in full. Treat its
> `Invariants` and `Anti-patterns` as binding. When your changes touch
> `tracked_sources`, update the concept file or include a
> `Concept-Verified: <ConceptName>` trailer in the commit message.

## Cognition

How the agent thinks: the loop, the routing, the memory, the differentiation, the trace.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [PropelLoop](propel-loop.md) | stable | Six-phase autonomous cognitive cycle (Perceive → Recall → Optimize → Plan → Execute → Loop). Recall must precede Plan. |
| [CognitiveRelay](cognitive-relay.md) | stable | Provider-agnostic LLM routing: declarative rules pick an `AIConfiguration` per `RoutingContext`. Cognition layer never imports provider SDKs. |
| [MemoryProvenance](memory-provenance.md) | stable | Episodic (Outcome) and semantic (Knowledge) memory cells. Every cell is timestamped, attributable, and indexed by `run_id` for time-travel. |
| [SparkSystem](spark-system.md) | stable | Cellular differentiation: Sparks layer onto a single agent class to narrow capability. Sparks can only narrow, never expand. |
| [DreamCycle](dream-cycle.md) | experimental | Async memory consolidation. Target shape only — no implementation yet. |
| [CognitionTrace](cognition-trace.md) | stable | Per-`run_id` Arc trace projection: phases, model invocations, memory writes, tool calls, Watt cost. The glass-brain read model. |

## Coordination

How agents reach consensus and avoid stepping on each other.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [EventSerialBus](event-serial-bus.md) | stable | The nervous system. Agents coordinate by publishing typed `Event`s, not by direct method calls. Bus only dispatches; persistence lives one layer up. |
| [CoordinatorDigestStep](coordinator-digest.md) | experimental | Anti-lazy-delegation primitive: a coordinator must produce a digest before re-delegating. Target shape only — no implementation yet. |

## Surface

How the cognitive substrate meets the user, the platform, and the plugin ecosystem.

| Concept | Status | One-line summary |
|---------|--------|------------------|
| [AgentSurface](agent-surface.md) | stable | Typed, serializable UI render request (Form, Choice, Confirmation, Card). Plugins emit; platform renderers translate. No platform types in the contract. |
| [PluginPermissions](plugin-permissions.md) | stable | Deterministic gate that runs *before* any plugin tool dispatch. Compares manifest + tool-requested permissions against user grants. |
| [Ampere](ampere.md) | stable | The meta-concept: what makes a framework an AMPERE framework. Glass brain, AniMA agents, electrical metaphor, event-first coordination. |
