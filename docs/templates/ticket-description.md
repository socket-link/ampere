# Ticket description template

The description body a dispatch-ready ticket carries. Copy everything below the
rule, replace every `<…>`, and delete nothing — an empty section is an answer
("no constraints", "nothing excluded") and a missing one is a gap.

The conventions this template implements, with the rationale for each, are in
[`docs/concepts/ticket-conventions.md`](../concepts/ticket-conventions.md).
Read that first if you are about to change this file.

## Before you save the ticket

Three of the seven conventions are ticket *metadata* and not part of the
description body, so the template cannot carry them:

- **Exactly one `wave:<id>` label.** The only supervisory fact the work source
  can filter server-side; a ticket without it is in no ready queue.
- **No gate label on a new ticket.** `gate:awaiting-verdict` and
  `gate:escalated` are applied when a ticket *stops*, never when it is filed.
- **Blocking relations, not prose.** "Depends on AMPR-123" in the body is
  invisible to the ready-queue rule, which filters on the relation.

## Section order is load-bearing

Keep the `ampere-scope` block above the Task Sequence. A description projects
onto `CanonWorkItem.description` through `CanonProse.bounded`, which keeps the
first 8,000 characters and drops the rest, so a scope block below a long task
sequence can be truncated out of the projection a supervisor reads.

---

**Model/effort recommendation:** <model>, <low|medium|high> — <one-line rationale: what makes this ticket need that much, or that little, thinking>

## Context

<Why this ticket exists. Name the ticket, verdict or recon finding it comes
from, and state any decision it depends on as already-decided rather than
re-opening it. Expand abbreviations and inline the facts a reader would
otherwise have to go and fetch — this section is what makes the ticket stand
alone.>

## Objective

<One or two sentences naming the deliverable, in the form "X in repo Y making Z
true". Not a restatement of the context.>

```ampere-scope
repos:
  - repo: <owner>/<name>
    paths:
      - <repo-relative glob this ticket writes>
      - <repo-relative glob this ticket writes>
exclusive: true # false only when every path is a file this ticket creates
```

## Task Sequence

1. <Step, as an imperative naming the file or symbol it produces.> *Validation:* `<the narrowest command that proves this step is done — ./gradlew :ampere-core:jvmTest --tests "*SomeTest*", not ./gradlew build>` *passes.*
2. <Step.> *Validation:* <a command, or an observable fact a reader can check without running anything.>
3. <Step.> *Validation:* <the check.>

## Technical Constraints

* <A boundary the implementation must respect, and why — a layer it may not import, a type it may not touch, a format it may not break.>
* <Public-repo language rules apply: no consumer-product names in code, docs or examples.>

## Out of Scope

* <Work a reasonable reader would assume is included and is not, with where it lives instead — another ticket, a later wave, or "deliberately never".>

---

*End of template. Everything above the second rule and below the first is the
description body.*
