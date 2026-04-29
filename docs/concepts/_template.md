---
concept: <PascalCaseName>
status: stable          # stable | experimental | deprecated
tracked_sources:
  - path/to/primary/file.kt
  - path/to/glob/**
related: [OtherConcept, AnotherConcept]
last_verified: YYYY-MM-DD   # placeholder; bump when you confirm or update this file
---

# <Concept Name>

## What it is

One paragraph. Plain definition. The shortest possible answer to *"what does this name refer to?"*.

## Why it exists

The rationale. This is what an agent would otherwise rederive every session by reading
the codebase. State the design pressure, what would happen without this primitive, and
what alternatives were rejected.

## Where it lives

Concrete file paths and key types:

- `path/to/main.kt` — the interface
- `path/to/main_impl.kt` — the production implementation
- `path/to/store.sq` — schema
- `path/to/tests/` — behaviour pinned by tests

## Invariants

Statements that must always be true. These are the rules agents violate when they go rogue.
Each invariant should be a short, testable claim — not a guideline.

- The first invariant.
- The second invariant.

## Common operations

How to do the things this primitive is for. Skim-readable; one short paragraph or a
fenced snippet per operation.

- **Add a step** — …
- **Subscribe to events** — …
- **Validate** — …

## Anti-patterns

Wrong assumptions caught in past PRs, with one sentence of reasoning each.

- *Pattern X* — why it's wrong.
- *Pattern Y* — why it's wrong.
