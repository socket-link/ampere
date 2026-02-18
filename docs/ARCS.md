# Arcs

An **Arc** is a workflow pattern that defines how agents coordinate to accomplish a goal. The term has a double meaning:

1. **Electrical arc**: A high-energy discharge between two points
2. **Character arc**: The transformation journey of a protagonist

Just as agents evolve through [Sparks](./CORE_CONCEPTS.md), they follow Arcs to move work from inception to completion.

## Arc Structure

Every arc has three phases:

### Charge
Building potential. The system prepares by understanding the project context and user's goal.

- Analyze project structure (from `AGENTS.md`, `README.md`, etc.)
- Parse and decompose the user's goal
- Spawn the agents defined for this arc

### Flow
Active work. Agents loop through perceive → remember → optimize → plan → execute cycles.

- Agents take turns based on orchestration type
- Each tick advances the global state
- Continues until success criteria are met

### Pulse
Convergence and delivery. The system evaluates completion and delivers results.

- Check success criteria (tests pass, goal complete)
- Deliver artifacts (git commit, create PR)
- Capture learnings for future runs

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

| Type | Behavior |
|------|----------|
| `sequential` | Agents take turns in defined order |
| `parallel` | All agents act simultaneously each tick |
| `graph` | Custom DAG with dependencies (future) |

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
