# AMPR-289 Recon: Work-Source Integration Surface (Ready-Queue, Claim, Status Semantics via Chassis SPI)

**Issue:** [AMPR-289](https://linear.app/miley/issue/AMPR-289/recon-work-source-integration-surface-ready-queue-claim-and-status)
**Author:** Miley Chandonnet (via Claude recon session)
**Date:** 2026-08-23
**Work-source:** Linear (team `Ampere`)
**Probe sandbox:** project ["AMPR-289 Recon Sandbox (disposable)"](https://linear.app/miley/project/ampr-289-recon-sandbox-disposable-delete-after-recon-a33085cc7bb2), fixtures AMPR-292…AMPR-298

All claims are labeled **Verified** (executed against the sandbox), **Inferred** (reasoned from verified evidence or repo code), or **Untested** (not executed; reason given). The call-by-call probe transcript is in the Appendix.

---

## Executive Summary

- **Transport verdict: bind `Transport.MCP`.** MCP is the only transport with a real implementation in the repo (`Transport.kt:78-79`), is `BIDIRECTIONAL` on `JVM_DESKTOP`/`MACOS` while `OAUTH_REST` is `CONSUME_ONLY` on every platform (`Transport.kt:83-122`), and every required semantic was exercised successfully over MCP. The OAuthRest path is additionally blocked operationally: **no local Linear API credential exists under any local secret convention** (Verified), so its claims stay Untested.
- **Linear provides no atomic claim.** The write surface has no precondition/compare-and-swap parameter; concurrent writes both succeed with last-write-wins and no conflict error (Verified). Claim safety must be built above the API — recommended protocol below.
- **The ready-queue predicate is not fully server-side expressible over MCP.** Positive filters (project, state, one label) work; label negation and "no open blockedBy" do not — the query over-approximates and requires client-side filtering with per-issue relation reads (Verified, N+1).
- **Status lifecycle:** Linear accepts any→any transition with no guards, auto-manages `startedAt`/`completedAt`, and records a timestamped `stateHistory` (Verified). The supervisor's 7-state lifecycle maps onto Linear's 5 workflow states plus label conventions.
- **Chassis SPI fit:** the SPI shape (PerceiveSource/ExecuteSink/WritableCanonAdapter) fits, but 5 framework gaps are identified, the largest being no conditional-write surface on `ExecuteSink` and `CanonWorkStatus` being too coarse for the supervisor lifecycle.

---

## 1. Required-Semantics Contract

Operations enumerated from the Rung 1–3 definitions on epic AMPR-286; every operation traces to a rung.

| Operation | Rung | Verdict |
| --- | --- | --- |
| Ready-queue query (unblocked + un-gated + in-wave) | R1, R3 | Expressible with client-side filtering (Verified) |
| Atomic claim | R1, R3 | **Not provided by API**; protocol required (Verified) |
| Status transitions (full lifecycle) | R1, R2 | Expressible; some states need label conventions (Verified) |
| Ticket-body + relation read (context assembly) | R1 | Verified (`get_issue` returns description, labels, relations, stateHistory) |
| Comment write-back (findings, escalations) | R2, R3 | Verified, byte-identical Markdown |
| blockedBy graph read | R3 | Verified per-issue; not batch-filterable over MCP |

### 1.1 Ready-queue query

Server-side expressible over MCP (`list_issues`): team, project, state, **one** positive label, assignee, pagination. **Verified** by executing `list_issues(project=sandbox, state=Todo, label=wave:w1)`.

Inexpressible predicates (flagged per ticket):

- **"No open blockedBy"** — no relation filter parameter exists. The executed query returned the deliberately blocked fixture AMPR-293. **Verified.**
- **"Not awaiting human verdict"** (label negation) — no negative label filter. The query returned the deliberately gated fixture AMPR-298 (`gate:awaiting-verdict`). **Verified.**
- Multi-label AND — single `label` parameter only. **Verified** (schema surface).

Consequence: the ready-queue is an **over-approximating server query + client-side filter**. `get_issue(id, includeRelations=true)` returns `relations.blockedBy` with each blocker's identity; blocker open/closed status requires resolving each blocker (or maintaining a wave-local cache). Cost is N+1 reads per poll cycle over the candidate set. **Verified** (AMPR-293 returned `blockedBy: [AMPR-292]`).

Linear's GraphQL `IssueFilter` may express more of this server-side (it documents relation and label-negation comparators): **Untested — blocked on credential** (see §2.3). If a key is provisioned, re-run this probe before implementation; it could eliminate the N+1.

### 1.2 Claim semantics

**The API provides no atomic claim and no conditional write.**

- The MCP write surface (`save_issue`) has no precondition, version, or expected-state parameter of any kind. **Verified** (schema of the executed tool).
- Two back-to-back writes to AMPR-295 (`In Progress` then `In Review`, same assignee) both returned success; the second silently overwrote the first; no conflict error, no version signal. Final state: `In Review`. **Verified.**
- Caveat: the two calls were serialized by the client (~1.7 s apart), so a truly simultaneous race was not exercised. "Both accepted, last-write-wins, no error" under simultaneity is **Inferred** from the verified absence of any precondition surface (there is nothing the server could reject on). Re-verify with parallel GraphQL mutations once a credential exists.

**Recommended claim protocol (claim-by-write-then-verify):** the concurrency guarantee the API *does* provide is that comments are append-only with a server-assigned, millisecond-resolution total order — two probe comments posted in quick succession came back with distinct, ordered `createdAt` values (02:23:26.769Z vs 02:23:27.486Z). **Verified.** Protocol:

1. Supervisor process posts a claim comment `claim:<issue>:<supervisor-instance-id>` and transitions the issue to the claimed state.
2. It re-reads the comment list; the earliest claim comment wins. Losers revert nothing (state converges to the same claimed state) and skip dispatch.

This makes double-dispatch structurally impossible as long as every claimant follows the protocol; a crashed claimant leaves a stale claim comment, which the interruption-fate work (AMPR-291) must account for.

### 1.3 Status transitions

Ampere team workflow states: `Backlog` (backlog), `Todo` (unstarted), `In Progress`, `In Review` (both started), `Done` (completed), `Duplicate`, `Canceled`. **Verified** (`list_issue_statuses`).

Walked AMPR-296 through Backlog → Todo → In Progress → In Review → Done → **Todo (reopen)**. Every transition accepted; **there are no transition guards — any state to any state is legal**, including reopening from Done. Linear auto-manages `startedAt` (set on first started-type state), `completedAt` (set on Done, cleared on reopen), and appends every hop to a timestamped `stateHistory`. **Verified.** The `stateHistory` gives the supervisor a free audit trail for detecting external state interference.

Lifecycle mapping (supervisor → Linear):

| Supervisor state | Linear representation | Basis |
| --- | --- | --- |
| queued | `Todo` | Verified |
| claimed | `In Progress` + assignee + claim comment | Verified mechanics; no native "claimed" state |
| in-progress | `In Progress` | Verified |
| verifying | `In Review` | Verified |
| verdict-requested | `In Review` + label `gate:awaiting-verdict` | Verified label mechanics |
| escalated | label `esc:chi` + escalation comment | Inferred (same mechanics as verified gate label) |
| done | `Done` | Verified |

The three supervisor states with no native Linear state (claimed, verdict-requested, escalated) ride on label + comment conventions — see §4.

### 1.4 Findings write-back

Posted a Markdown-rich comment (headings, nested lists, task lists, fenced Kotlin block, table, blockquote, strikethrough, links, `<angle brackets>`, `&amp;`, emoji, non-ASCII) to AMPR-297 and read it back via an independent `list_comments` call: **byte-identical round-trip**. **Verified.** Markdown fidelity is sufficient for claim-labeled findings comments.

Side observation (**Verified**): the workspace has Linear→GitHub issue sync enabled; every sandbox issue and comment thread was mirrored into `socket-link/ampere` GitHub issues (#704–#709) automatically. Supervisor writes to Linear are therefore **not private to Linear** — they propagate to the public repo. This must be treated as part of the write contract (and the sandbox's GitHub mirrors need cleanup along with the sandbox).

---

## 2. Transport Verdict: **MCP**

### 2.1 Evidence for MCP

- **Repo capability table** (`ampere-core/src/commonMain/kotlin/link/socket/ampere/link/Transport.kt`): `MCP` is the only member with `hasImplementation == true` (L78-79); `MCP` is `BIDIRECTIONAL` on `JVM_DESKTOP` and `MACOS` (L86-88) — the supervisor's platforms — while `OAUTH_REST` is `CONSUME_ONLY` on every platform (L91). **Verified** (code).
- **`PlugContext.create`** already validates a manifest, resolves links, connects `McpServerDependency` entries, and lists tools (`plug/PlugContext.kt:72+`) — the MCP consumption path is wired end-to-end. **Verified** (code + `PlugContextEndToEndTest`).
- **All four semantic areas** of the contract were exercised successfully over MCP in this recon (§1). **Verified.**
- **Auth:** MCP reaches Linear through hosted OAuth (the connector authenticates as the operating user); no raw secret handling in the adapter. **Verified** in this session's configuration.

### 2.2 Caveats on MCP (carried into implementation)

- Ready-queue requires client-side filtering (§1.1) — **Verified**.
- Rate limits over MCP: never hit during ~30 probe calls; actual limits **Untested**.
- The MCP tool schema is vendor-controlled and can change without an API version contract; REST/GraphQL has explicit versioning. **Inferred** risk; mitigate by pinning the tool-surface expectations in adapter tests.
- This session used the claude.ai-hosted Linear connector; a headless supervisor would bind Linear's own MCP endpoint. Tool-surface parity between the two is **Untested**.

### 2.3 Why OAuthRest loses (today)

- **No local credential exists.** Exhaustive search of the local secret conventions (macOS keychain generic passwords, `~/.netrc`, `~/.env`/repo `.env*`, `~/.config`, `~/.claude.json`, `~/.mcp-auth`) found no Linear API key or OAuth token. **Verified.** An unauthenticated GraphQL call returns a clean 401 (`AUTHENTICATION_ERROR`) with an `x-request-id` and no rate-limit headers (transcript: `.context/ampr-289-probes/`). Everything requiring auth on this transport — query expressiveness, claim behavior, rate limits, pagination — is therefore **Untested (blocked)**, and per the ticket's own rule cannot be graded up from documentation.
- `Transport.OAUTH_REST` has no transport implementation in the repo and `CONSUME_ONLY` capability on all platforms. **Verified** (code).
- Note for the verdict discussion: Linear's non-MCP API is **GraphQL, not REST** — if this surface is ever bound, the `OAUTH_REST` enum member's semantics ("OAuth-authenticated HTTPS API") stretch but hold. **Verified** (401 probe hit `https://api.linear.app/graphql`).

**Verdict:** bind `Transport.MCP` for the v1 adapter. Revisit only if the N+1 ready-queue cost measured in practice exceeds the poll budget — in which case provision a credential and re-run the §1.1 probe against GraphQL `IssueFilter` first.

---

## 3. Chassis SPI Fit Assessment

The adapter shape the SPI already supports well (**Verified**, code):

- `PerceiveSource<CanonWorkItem>` for the ready-queue: `PerceiveQuery.cursor`/`PerceivePage.nextCursor` match Linear pagination; `partialFailures` is the right channel for per-issue projection failures during the N+1 relation resolution (`plug/spi/PerceiveSource.kt:40-93`).
- `ExecuteSink<C>` for claim/transition/comment writes (`plug/spi/ExecuteSink.kt:19-26`); `WritableCanonAdapter.ownedFields` + `mergeForWriteBack` (`canon/adapter/WritableCanonAdapter.kt:36-120`) is exactly the preserve-and-merge needed to write status/assignee without clobbering vendor-owned fields.
- The AMPR-263 three-layer capability-gating pattern (`canon/table/TableWriteSink.kt:31-56` + `PlugManifest.tableWriteCapabilities` + `PlugManifestValidator.validateTableWriteCapabilities`) is a ready template for gating supervisor writes.

Framework gaps (each cites the SPI symbol that falls short; all **Verified** against code unless noted):

| # | Gap | Symbol | Why the adapter needs it |
| --- | --- | --- | --- |
| G1 | No conditional-write / CAS surface | `ExecuteSink.execute(command): Result<ExecuteReceipt>` (`ExecuteSink.kt:19-26`) | Claim semantics (§1.2) need "write only if still unclaimed" or at minimum a receipt that reports the pre-write state. Today an adapter can only bolt the claim protocol on privately; the SPI cannot express or enforce it. |
| G2 | `CanonWorkStatus` too coarse | `CanonWorkStatus { BACKLOG, TODO, IN_PROGRESS, DONE, CANCELLED }` (`canon/CanonRing3Entities.kt:382+`) | Cannot represent claimed / verifying / verdict-requested / escalated. Only `providerStatus: String?` and `labels` carry them, which pushes supervisor lifecycle semantics into stringly-typed fields. |
| G3 | Stringly-typed query filters, no negation | `PerceiveQuery.filters: Map<String, String>` (`PerceiveSource.kt:68-75`) | The ready-queue predicate includes negations ("no open blockedBy", "not gated") that a `Map<String,String>` cannot express structurally; each adapter invents a private filter mini-language. |
| G4 | No over-approximation signal | `PerceivePage` (`PerceiveSource.kt:89-93`) | When the source cannot evaluate the full predicate server-side (§1.1), the page has no way to declare "results are unfiltered on predicates X, Y" — callers cannot distinguish exact from over-approximate results. |
| G5 | Receipt carries no outcome state | `ExecuteReceipt(linkId, executedAt, handle)` (`ExecuteSink.kt:39-43`) | Last-write-wins sources (§1.2) require the writer to learn the post-write state to detect lost claims; the receipt cannot carry it, forcing an extra perceive round-trip. |

Additional operational finding: `Link.credentialRef` points at a `keychainAlias` (`link/Link.kt:86`), but no Linear alias exists in the local keychain (**Verified**, §2.3) — provisioning a credential store entry is a prerequisite for any non-MCP binding, and there is no framework-side provisioning flow (Inferred gap, lower priority while MCP is the binding).

---

## 4. Ticket-Metadata Gap List

Each gap names the supervisor behavior that fails without it. Linear issues have no arbitrary custom fields, so mechanisms are labels, description blocks, or comments (Inferred from product surface; label + description mechanics Verified in §1).

| # | Missing metadata | Failing supervisor behavior | Candidate mechanism | Recommendation |
| --- | --- | --- | --- | --- |
| M1 | Machine-readable scope declaration (Contract 3) | R3 wave orchestration cannot guarantee non-overlapping scopes → parallel dispatch is unsafe | Fenced ` ```ampere-scope ``` ` YAML block in the description (descriptions round-trip Markdown — Verified) vs. label convention | **Description block**; scopes are structured data, labels can't hold paths |
| M2 | Wave tag | R1/R3 ready-queue cannot scope to the active wave (fixture AMPR-294 proved un-tagged tickets are indistinguishable without it) | Label `wave:<id>` — Verified filterable server-side | **Label** |
| M3 | Human-verdict gate marker | R3: gated recon tickets would be dispatched as ready (fixture AMPR-298) | Label `gate:awaiting-verdict` — Verified creatable/applicable; **not** server-side negatable → client-side filter | **Label**, filtered client-side |
| M4 | Verification-manifest pointer | R2 cannot locate DoD gates | Per D3 the manifest is in-repo (`.ampere/verify.yml`), so the ticket needs no pointer unless overriding | **None needed for v1**; optional `verify:` key in the M1 block |
| M5 | Claim-owner identity | All supervisor processes authenticate as one Linear user → assignee cannot distinguish them; lost-claim detection (§1.2) impossible | Claim comment `claim:<issue>:<instance-id>` — comment total order Verified | **Claim comment** (doubles as the claim-protocol arbiter) |
| M6 | Escalation payload | R2 CHI escalation needs Oscilloscope context attached to the ticket | Structured comment with `esc:` prefix + label | **Comment + label** (same mechanics as M3/M5) |

---

## Technical Guidelines (for the implementation wave — not scaffolded here)

- Bind `LinkRequirement(transport = Transport.MCP, direction = READ_WRITE, role = CONSUMER, minimumScope = {WORK_ITEM, …})`; matching `McpServerDependency` name is required by `PlugContext.create`.
- Ready-queue = server query (project + state + `wave:` label) → client filter (drop `gate:*`, resolve `blockedBy` open-ness) → report dropped candidates via `PerceivePage.partialFailures` only for *errors*, never for filtered items.
- Never trust a claim write's success as claim ownership; always re-verify (§1.2 protocol).
- Treat Linear→GitHub sync as part of the write blast radius (§1.4).

---

## Appendix: Probe Transcript (condensed)

All calls executed 2026-08-23 (UTC 2026-08-24 02:20–02:24) against the sandbox project. MCP = claude.ai Linear connector; GQL = `api.linear.app/graphql` via curl (transcript files: `.context/ampr-289-probes/headers-0.txt`, `resp-0.json` — not committed).

| # | Call | Result |
| --- | --- | --- |
| 1 | GQL `{ viewer { id } }`, no credential | 401 `AUTHENTICATION_ERROR`, `x-request-id` present, no rate-limit headers |
| 2 | MCP `save_project` → sandbox project | created `f808f5f4…` |
| 3 | MCP `create_issue_label` ×2 (`wave:w1`, `gate:awaiting-verdict`) | created |
| 4 | MCP `save_issue` ×7 → fixtures AMPR-292 (ready), 293 (blockedBy 292), 294 (no wave), 295 (claim target), 296 (lifecycle), 297 (comment), 298 (gated) | created; GitHub mirrors #704–#709 auto-created by sync |
| 5 | MCP `list_issues(project, state=Todo, label=wave:w1)` | returned 292, 293, 295, 298 — blocked and gated fixtures **not** excluded |
| 6 | MCP `save_issue(295, In Progress, assignee)` then `save_issue(295, In Review, assignee)` | both succeeded; no conflict; final `In Review` |
| 7 | MCP `get_issue(293, includeRelations)` | `blockedBy: [AMPR-292]` |
| 8 | MCP `save_comment(295)` ×2, claim markers | distinct ordered `createdAt`: …26.769Z, …27.486Z |
| 9 | MCP `save_issue(296)` walk Backlog→Todo→In Progress→In Review→Done→Todo | all accepted; `stateHistory` recorded 6 entries; `completedAt` set on Done, cleared on reopen |
| 10 | MCP `save_comment(297)` rich Markdown + `list_comments(297)` | byte-identical round-trip |

---

## Next Steps

1. **Human verdict on this recon** (posted to AMPR-289) — per task sequence step 5, no adapter scaffolding until then.
2. Decide whether to provision a Linear API credential (keychain alias per `CredentialRef` convention) to close the Untested GraphQL items — chiefly whether `IssueFilter` can push the blocked/gated predicates server-side and kill the N+1.
3. Feed gaps G1–G5 into framework ticket drafting; feed M1–M6 into the ticket-template conventions for the implementation wave.
4. Delete the sandbox project (AMPR-292…298) **and** its auto-synced GitHub mirrors (#704–#709) after the verdict.

---

*Document generated as part of recon for AMPR-289.*
