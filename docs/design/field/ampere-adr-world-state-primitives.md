# ADR: World-state primitives — the Field

**Status:** Accepted (2026-10-11). Proposed 2026-09-20.
**Decision register:** the F-register on [AMPR-328](https://linear.app/miley/issue/AMPR-328) — every row of this ADR is one F row there, and the register's Verdict column, not this file, is where a row is locked, amended or rejected. Rows cited below as F*n*.
**Implementation:** `ampere-core/src/commonMain/kotlin/link/socket/ampere/field/` ([AMPR-415](https://linear.app/miley/issue/AMPR-415), F6 + F27).
**Companion artifacts:** [`FieldPrimitives.kt`](FieldPrimitives.kt) (the sketch), `recon/field-foundation.md` (the recon; see [README](README.md) for why it is not committed here).

---

## Context

Ampere's differentiator, with third-party harnesses (OpenCode, Claude Code, Hermes)
owning execution, is in-loop metacognition: reasoning about world state and confidence
*before* acting. The four-slice recon (R0–R10) found every piece of that story present
as a shape and none of it wired:

- Events are the only durable record, but only one publisher persisted them (C2) and
  ordering was millisecond timestamps with no sequence (C4). *Fixed in W1 (F1, F2).*
- No stored memory recorded who holds it (C13).
- No constraint predicate was stored as a lambda — constraints were data that nothing
  read (C19); the only live gate was `PlugPermissionGate` (C20).
- No candidate action carried a confidence before it executed (C21, C22).
- Nothing stood between "candidate chosen" and "candidate executed" (C31, C32).

The structural pattern behind the findings: Ampere has two of everything — two loops,
two domain models (`agents.domain` vs `canon`, C42), two execution paths, two
`ask_human` tools, two knowledge stores, two event hierarchies. The Field is the merge
point.

## Decision

**The Field** is Ampere's world state at any instant: a deterministic fold of persisted
Events into stored state. The fold produces six kinds of record over three kinds of
primitive.

### Primitives (F6)

Three top-level branches of a sealed `Primitive`, each with identity (`Ref<out T :
Primitive>`, a value class over the id string replacing the `String` typealiases):

| Branch | What it is | Source of truth |
|--------|-----------|-----------------|
| `Subject` | Who acts and believes: `Agent`, `Human`, `Group` | Registered (F30, `SubjectStore`) |
| `Goal` | What is acted toward | The Arc's `GoalTree` / `GoalNode`, persisted (F8) |
| `Environment` | What is acted on and with | The canon *is* this branch (F7); the package declares a marker only |

Role-by-subject is the reason there are three branches: the loop is written once per
branch, not once per role an agent might play.

### Records about primitives (F6, F9–F12)

| Record | Shape | Rule |
|--------|-------|------|
| `Belief` | holder `Ref<Subject>`, subject `Ref<Primitive>`, content, `kind {LESSON, IDEA, PERCEPTION, PLAN, TASK, OBSERVATION}`, `confidence: Double?`, `sensitivity {PUBLIC, PRIVATE, PROTECTED}`, `decay`, provenance | `Settled` carries a number; `Unsettled` carries `null` by construction. Every belief has a holder (F9). Confidence is a stored, already-mapped number (F10); `PROTECTED` rows leave no Subject's view but the holder's and are never judged off-device (F12). `kind` is required, with no default (AMPR-448); outcome knowledge is a `LESSON` (F21). |
| `Constraint` | subject, `rule: Rule`, `hardness {HARD, SOFT}`, origin `Ref<Subject>`, provenance | The predicate is derived from the `Rule` at probe time by the Breaker and is **never stored** — not as a lambda, not as an expression. Hard rules are never model-evaluated (F15, J7). |
| `Rule` | sealed: `Requirements`, `Quota.{Time, Count, Tokens}`, `Transition`, `AccessPolicy`, `Dependency`, `Deadline`, `Cadence` | Each case is the data form of a rule the codebase already enforces or declares unread (F14, F15). |
| `Action` | id, `actor: Ref<Subject>`, `executor: String?`, goal, `state {PROPOSED → ADMITTED → TAKEN}`, `reversibility?`, `cost?`, `prediction?`, `result?: ActionResult {Succeeded, Failed(reason?)}`, provenance | Plan steps are the model's proposed Actions; no separate candidate type (F16). Actor and executor are separate members (F17), filled from the live path since AMPR-443. `null` reversibility or cost means unknown, never reversible or free (AMPR-448). Only a `TAKEN` Action has a result, so a failure is a taken Action whose result failed (F20), not a state after `TAKEN`. |
| `Relationship<From, To>` | from, to, `kind {Mirrors, MemberOf}`, provenance | Structure is edges, not nested members: a tracker's work item `Mirrors` a Goal (F8); an agent is `MemberOf` a Group (F30). |
| `Provenance` | `createdBy` (the producing event's id), `createdAt` | Lives on the event envelope (F2); every record points at one event id and the causal chain is a walk over `caused_by`. |

### The retypes are fold-side (Q5, 2026-10-11)

F20 and F21 each named a retype for after W2: the tool events "are `ActionTaken` /
`ActionFailed`", and Pulse's learnings become `BeliefFormed(Lesson)`. The ruling on AMPR-328
question Q5 is that **both retypes happen in the fold, and no event class carries them**, which
keeps F2's "zero changes to the concrete event classes" (AMPR-448):

- A `ToolExecutionStarted` / `ToolExecutionCompleted` pair *reads as* an `Action` in `TAKEN`
  state. The actor is the pair's event source, the executor its `executorId`, the provenance the
  `Started` event, and the result `Succeeded` or `Failed(errorMessage)`. A held completion is not
  a taken Action.
- A `KnowledgeStored` of a `Knowledge.FromOutcome` row *reads as* a `Belief` of kind `LESSON`.
  Every other knowledge source reads as its namesake kind.

No `ActionTaken`, `ActionFailed` or `BeliefFormed` event exists, and none is planned. A reader
who wants Actions or Lessons asks the Field (`FieldService.snapshot`), never the event log by
those names.

### Signals are derived, never stored

Priority, urgency and trust are computed from Beliefs, Constraints and Commitment
history at read time. No record has a `priority`, `urgency` or `trust` member.

### Ranking and the gate (F13, F16, F27, F28)

Candidate Actions are ranked by **Potential** against **Resistance** (electrical
naming, F27 — in the family of Transformer, Cell, Switch, Relay, Breaker). The top
admissible candidate executes without deliberation unless ranking is degenerate —
blocked, ambiguous, plateau, oscillating, uncertain — and those five conditions are
the only triggers for a planner. A **Breaker** gates a Proposed Action against
Constraints and returns an `Admission { Pass, Resist(degree), Trip(reason) }`; probes
measure, breakers protect (F13). `Potential` and `Resistance` are declared as names
over a `Double` only: the arithmetic (difference vs ratio; depth-derived vs
Principal-set goal weight) is F28, open, and settled empirically in `ampere-eval`.

### The loop

PROPEL — Perceive, Recall, Observe, Plan, Execute, Learn — is the six-phase cognitive
loop. A PROPEL tick is the unit of cognition; a run is the unit of lifecycle and
learning, and the run boundary stays a projection over the fold, never a substrate
(AMPR-281). The Field folds over all events; a run is a filter.

### Dependency direction

`link.socket.ampere.field` is a leaf: `events`, `messages`, `meetings`, `tickets`
depend on it, never the reverse. A grep-gated boundary test enforces it.

## Consequences

- **Two models become one.** `agents.domain` types that describe world state migrate
  onto Field records over W2–W4; the canon is adopted as the Environment branch
  rather than re-declared.
- **Memory gains a holder, a confidence column and a sensitivity column** from the
  first migration (AMPR-417). Old rows are backfilled to a `system` pseudo-Subject.
- **Constraints become evaluable.** `ExecutionConstraints` retires into `Rule`
  (F14); `PlugPermissionGate` becomes the first Hard `Rule.AccessPolicy` (F15);
  `TicketStatus.validTransitions()` becomes a `Rule.Transition`.
- **A step exists between chosen and executed.** The Breaker sits at
  `SparkBasedAgent.executePlanStep` (F16); every plan step is an Action in `PROPOSED`
  state until admitted.
- **Who did this becomes answerable.** Actor and executor are distinct on every
  Action (F17).
- **Nothing is serialized by this ADR.** Wire shape is a storage decision taken per
  store; the primitives carry no `@Serializable`.

## Alternatives rejected

- **`WorldState` / `Score` / `Penalty` naming.** Rejected (F27): the electrical
  metaphor is load-bearing in the ranking model and already names the rest of the
  runtime.
- **Predicates stored on Constraints.** Rejected: a stored predicate cannot be
  inspected, diffed between folds, or rendered in the glass brain. A stored Rule can be
  all three.
- **Re-declaring Environment types in the Field.** Rejected (F7): 33
  provenance-carrying, wire-serializable canon types with adapters already exist.
- **A separate candidate type for ranking.** Rejected (F16): plan steps are already
  the model's proposed Actions.
- **One string for actor and executor.** Rejected (F17): it is what made "who did
  this" unanswerable (C39).
- **Reusing `Verdict` for the gate's result.** Rejected (F13): `Verdict` already rides
  inside a published Event; two meanings under one name would poison the event log.
  The gate returns `Admission`.
- **Declaring the ranking operator now.** Rejected (F28): no evidence yet; the
  operator is settled in `ampere-eval`, and declaring it in `ampere-core` first would
  bake the answer in.

## Out of scope for the ADR's first implementation (AMPR-415)

`FieldService` and the fold; persistence tables (AMPR-416, 417, 419); `Admission` and
the Breaker (W3, F13/F16); ranking arithmetic (F28); the `Affordance` unification
(F24), bakes (F25) and the tool/plug convergence (F26), each its own ADR.
