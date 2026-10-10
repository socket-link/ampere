# Arcs

An **Arc** is a workflow pattern that defines how agents coordinate to accomplish a goal. The term has a double meaning:

1. **Electrical arc**: A high-energy discharge between two points
2. **Character arc**: The transformation journey of a protagonist

Just as agents evolve through [Sparks](./CORE_CONCEPTS.md), they follow Arcs to move work from inception to completion.

## Arc Structure

Every arc has three phases:

> **Status (verified against `main`, 2026-10-10).** The three phases run, but
> several of the behaviours below are narrower in code than the words suggest.
> Each one is flagged inline with the ticket that closes it; the parent epic is
> [AMPR-385](https://linear.app/miley/issue/AMPR-385). Read
> [`docs/concepts/propel-loop.md`](concepts/propel-loop.md) for what a consumer
> can drive today.

### Charge
Building potential. The system prepares by understanding the project context and user's goal.

- Analyze project structure (from `AGENTS.md`, `README.md`, etc.)
- Decompose the user's goal into a `GoalTree`. This is a regex split on
  `and then` / `then` / `and` / `;` / newline (`ChargePhase.kt:334-338`), not a
  model call — one goal node per clause.
- Spawn the agents defined for this arc: one `SparkBasedAgent` per configured
  role, with the project spark, the role spark and any extra sparks stacked
  (`ChargePhase.kt:345-371`). Each spawn gets a fresh id; there is no agent
  registry and no identity across runs.

### Flow
Active work. Agents take turns, each turn running perceive → recall → plan → execute.

- Agents take turns in the order the orchestration declares. Only `sequential`
  is implemented — see *Orchestration Types* below.
- Each tick advances the global state
- Runs until the goal tree is marked complete or `maxFlowTicks` is reached
  (default 100). Each tick takes its task from the Arc's current goal since
  [AMPR-395](https://linear.app/miley/issue/AMPR-395)
  (`FlowPhase.taskForCurrentGoal`, `:267-278`) — before that it read the agent's
  own memory cell, which nothing on this path writes, so the goal never arrived.
  One caveat remains: a goal is marked complete on *any* successful outcome,
  with no check that it was met (`FlowPhase.kt:298-310`).
- A tick has no Observe step, and no Learn step of its own — the Arc's closing
  phase is Pulse, once per run rather than once per tick. The comments calling
  the tick's steps "remember" and "optimize" are pre-PROPEL naming
  (`FlowPhase.kt:221, 224`).

### Pulse
Convergence and delivery. The system evaluates completion and delivers results.

- Check success criteria: goals completed vs. total, and zero failed outcomes.
  "Tests pass" is a heuristic — whether the Arc has an agent whose role is one
  of `qa` / `quality` / `validator` / `test`, and if so whether any outcome
  failed (`PulsePhase.kt:127-132`).
- Deliver artifacts (git commit, create PR) — **not implemented**.
  `PulseResult.commitSha` and `prUrl` are always null and marked "Populated
  externally" (`PulsePhase.kt:101-102`); no caller populates them.
- Capture learnings for future runs: one `Knowledge` entry per successful outcome, stored through
  the producing agent's memory service and tagged with the run id, so the next run's Recall finds
  it (`PulsePhase.kt:196-233`, AMPR-402). The write needs a `KnowledgeRepository` on the runtime —
  `ArcSession.create` supplies one when it has a `database`; without it the entries come back
  `Learning.stored = false`. A cancelled or failed run never reaches Pulse and owes a
  `CompletionManifest` instead.

## Built-in Arcs

| Arc | Agents | Use Case |
|-----|--------|----------|
| `startup-saas` | PM → Code → QA | Product development, ticket completion |
| `devops-pipeline` | Planner → Executor → Monitor | Deployments, infrastructure, incidents |
| `research-paper` | Scholar → Writer → Critic | Technical writing, documentation |
| `data-pipeline` | Analyst → Engineer → Validator | ETL, analytics, ML experiments |
| `security-audit` | Scanner → Analyst → Remediator | Vulnerability scanning, compliance |
| `content-creation` | Researcher → Writer → Editor | Marketing, blog posts, docs |

## Usage

### Zero-config (recommended)
```bash
ampere                          # Uses startup-saas by default
ampere --arc devops-pipeline
```

### Custom override
Create `.ampere/arc.yaml` to customize:

```yaml
# Only specify what differs from default
name: startup-saas

agents:
  - role: pm
  - role: code
    sparks: [rust-expert]  # Add extra spark
  - role: qa
```

## Orchestration Types

| Type | Behavior | Status |
|------|----------|--------|
| `sequential` | Agents take turns in defined order | Implemented |
| `parallel` | All agents act simultaneously each tick | **Not implemented** |
| `graph` | Custom DAG with dependencies | **Not implemented** |

`FlowPhase.execute` rejects anything but `SEQUENTIAL` up front —
`require(arcConfig.orchestration.type == OrchestrationType.SEQUENTIAL)`
(`FlowPhase.kt:111-113`) — so a `parallel` or `graph` arc config fails the run
rather than degrading to sequential. The per-tick barrier that parallel
execution would need exists but is a deliberate no-op (`FlowPhase.kt:312-318`).

## Configuration Reference

See [`arcs/`](./arcs/) for complete examples of each built-in arc.

Minimal arc configuration:

```yaml
name: my-arc
description: What this arc does

agents:
  - role: agent-role-name
    sparks: []  # Optional extra sparks

orchestration:
  type: sequential
  order: [agent1, agent2, agent3]
```
