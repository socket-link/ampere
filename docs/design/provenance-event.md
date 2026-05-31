# `MemoryEvent.ProvenanceCommitted` — Design Placeholder

**Issue:** [AMPR-176](https://linear.app/miley/issue/AMPR-176) (forward-looking design placeholder)
**Status:** Not implemented. Design sketch only — promote to an implementation ticket when one of the trigger conditions in [Promotion criteria](#promotion-criteria) fires.
**Related work:** [AMPR-168](https://linear.app/miley/issue/AMPR-168) (Wave 3 cognitive event audit, Gap-5), [AMPR-175](https://linear.app/miley/issue/AMPR-175) (`MemoryEvent.MilestoneReached`), [AMPR-169](https://linear.app/miley/issue/AMPR-169) (Phosphor / Lumos bridge).
**Last verified:** 2026-05-30

---

## Context

The Wave 3 cognitive event audit (AMPR-168) flagged that the original Wave 3 plan referenced "blockchain provenance entries" without confirming whether such an event existed. It does not. `MemoryEvent` today carries only `KnowledgeStored` and `KnowledgeRecalled` (see `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/event/MemoryEvent.kt`). The `OutcomeRecorded` event referenced in `docs/concepts/memory-provenance.md` is itself aspirational.

The Wave 3 Lumos bridge ships without provenance-chain semantics. Routine milestones — the STAR glyph signal — are covered by `MemoryEvent.MilestoneReached` (AMPR-175). Provenance is a *separate* axis: a verifiable cross-history chain for every memory write, hash-linked back to its parent, optionally signed.

This document captures the design space so the work is not re-derived from scratch when AMPERE grows that surface.

---

## Suggested event shape

```kotlin
@Serializable
data class ProvenanceCommitted(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,

    // Identity of the committed memory entry.
    val entryId: String,                    // KnowledgeId or OutcomeId — depends on publish-site choice.
    val entryKind: ProvenanceEntryKind,     // KNOWLEDGE | OUTCOME | CHECKPOINT
    val knowledgeId: String? = null,        // present when entryKind == KNOWLEDGE
    val taskId: String? = null,             // optional — present when the commit was task-scoped

    // Chain linkage.
    val parentHash: String?,                // null only for genesis; otherwise the prior entry's contentHash
    val contentHash: String,                // canonical hash of (entryId, payload, parentHash, timestamp)
    val signer: SignerId,                   // who signed; opaque ID (agent / device / key)
    val signature: ByteArray? = null,       // optional in early designs; required once signing is live

    val runId: String? = null,              // preserves ArcTraceProjection wiring
) : MemoryEvent
```

`ProvenanceEntryKind` is a sealed enum (`KNOWLEDGE`, `OUTCOME`, `CHECKPOINT`) so consumers can filter without inspecting payload shape. `SignerId` is intentionally opaque — leave the key-material story to the implementation ticket.

The exact field set is **not load-bearing** at this stage. The point of fixing a sketch now is so future code-review on the implementation ticket has a fixed reference to argue against, not so this becomes the schema.

---

## Hash / signature scheme

**Working assumption:** Ed25519 signatures over a Merkle-DAG-style parent-hash chain (each entry references one parent by `contentHash`; checkpoint entries may reference multiple parents, forming a DAG rather than a strict list).

**Why Ed25519:** small keys, small signatures, fast verification, well-supported in KMP targets via `kotlin-crypto` or platform-actual wrappers. No hard requirement — Schnorr or post-quantum schemes are equally compatible with the chain shape.

**Why a parent-hash chain, not a Merkle tree:** the agent writes entries serially. There is no batch-verification motivation today. A simple `parentHash` link gives append-only tamper detection at minimal complexity; the DAG generalisation is reserved for checkpoint entries that summarise N predecessors.

**Explicitly out of scope at this stage:**
- Choice of hash function (BLAKE3, SHA-256, both — open).
- Key custody / rotation story.
- Off-chain anchoring (timestamp-server, public-ledger anchoring) — none of which is in scope unless a consumer pulls.

---

## Publish site

Three plausible patterns. The implementation ticket picks one.

**A. Co-located with every memory write.** Every `KnowledgeRepository.storeKnowledge` and every `OutcomeMemoryRepository.recordOutcome` emits one `ProvenanceCommitted`. Highest fidelity, highest event volume.

**B. Checkpoint-only.** Provenance entries are emitted only at *checkpoints* — e.g., end-of-phase, end-of-run, or explicit `flushProvenance()` calls. Lower volume; each checkpoint hash covers a batch of writes via DAG fan-in. Aligns better with the OpenAI grant's formal verification cadence (one proof obligation per checkpoint, not per write).

**C. Hybrid.** Per-write commit for `Knowledge`; checkpoint-only for `ExecutionOutcome` (which can be high-frequency). Operationally cheap, semantically awkward — two chains to reason about.

**Recommendation for the implementation ticket:** start with **B (checkpoint-only)**. Per-write provenance can be added incrementally without breaking the chain shape; checkpoint-only is the cheaper default and matches the verification workstream's natural granularity.

---

## Relationship to OpenAI grant — formal verification workstream

Open question for the implementation ticket: **does AMPERE need `ProvenanceCommitted` before formal verification work begins, or does the event shape *emerge from* that work?**

Two paths:

1. **Event first.** Ship `ProvenanceCommitted` with a conservative shape; let the verification workstream consume it. Risk: the verifier needs a field we did not include, and we churn the event shape early in its life.
2. **Verifier first.** Let the formal verification workstream define what it needs to prove (chain integrity, signer attribution, replay determinism); derive `ProvenanceCommitted` from those proof obligations. Risk: the verification workstream blocks on event design that nobody else is paid to do.

Path 2 is preferred *unless* a non-verification consumer (see below) creates pull first. The event exists to be verified; designing it without the verifier's input is speculative.

---

## Promotion criteria

This ticket may be promoted from "design placeholder" to "implementation" when **any one** of these fires:

- **Act 5 closes** and the next phase (Act 6?) opens its scope discussion — provenance is a natural Act 6 candidate.
- **OpenAI grant work begins concretely** and provenance becomes a deliverable in workstream-1 (formal verification).
- **Consumer pull arrives** — most likely Socket's "time-travel debugging" feature, which already reads `ArcTraceProjection` and would benefit from a tamper-evident chain. CHI / interpretability is a secondary consumer.

Until one of these fires, the cost of speculative implementation (event shape churn, premature commitment to a signature scheme, write-amplification on the memory path) exceeds the value.

---

## What this design does *not* lock in

- The exact event payload — fields above are a sketch, not a contract.
- The hash function or signature scheme.
- Whether `ProvenanceCommitted` extends `MemoryEvent` or becomes its own sealed family. The current proposal keeps it under `MemoryEvent` because the publish site is the memory layer; a `ProvenanceEvent` family is reasonable if non-memory commits (config, plan, identity) join the chain.
- Batching, retention, and pruning behaviour.
- The wire format used by any future Lumos / Phosphor bridge — Wave 3's STAR glyph signal is `MilestoneReached`, not `ProvenanceCommitted`.

---

## Implementation ticket — checklist seed

When the implementation ticket is filed, it should at minimum:

1. Pick one of the three publish-site patterns and justify the choice against the then-current consumer set.
2. Pick a hash function and signature scheme (or explicitly defer signing to a follow-up).
3. Define `ProvenanceEntryKind` and the genesis-entry convention.
4. Add `provenance_store.sq` (SQLDelight schema) carrying `entry_id`, `parent_hash`, `content_hash`, `signer`, `signature`, `run_id`, `timestamp`.
5. Update `docs/concepts/memory-provenance.md` to make the chain an invariant rather than an aspiration.
6. Add `ArcTraceProjection` support so a time-travelled run includes its provenance entries.
7. Remove the `TODO(AMPR-176)` marker in `MemoryEvent.kt`.

---

*Design placeholder filed for AMPR-176. No code changes accompany this document beyond the TODO marker in `MemoryEvent.kt`.*
