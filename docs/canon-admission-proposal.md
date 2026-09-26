# Canon admission proposal — template

This is the whole extension API. There is no other one.

Ampere's domain canon is a closed set (`CanonType`, `CanonEntity`), and it stays
closed: no consumer, Plug, Arc, or bundle can declare a new noun, and none can
substitute its own type for a canon member. The canon grows *upward* — by
admitting a noun into the shared core, in a versioned minor — never by pushing
vocabulary out to consumers. So the way a third party adds a noun is to propose
it here, against the four gates below, and the way a third party ships data that
does **not** clear the gates is to keep it consumer-side (`PerceiveSource<T>` and
`ExecuteSink<C>` are deliberately unbound, and `PlugManifest.isCanonExternal`
declares that choice honestly). See the *grows-upward* invariant in
[`docs/concepts/domain-canon.md`](concepts/domain-canon.md).

**Worked example:** [`docs/ampr-262-knowledge-work-canon-wave.md`](ampr-262-knowledge-work-canon-wave.md)
— the knowledge-work wave, which admitted four types, declined one, and deferred
one, with the evidence for each. Read it before filling this in; a proposal that
looks like its §1 tables is a proposal that can be decided.

## Read this first: nothing admitted is ever removed or renamed

`@SerialName` and `wireName` are wire contracts. Admitted types and fields land
in recorded traces and persisted rows, and `PlaybackRelay` replays those. So:

- A noun, once admitted, is **permanent**. Renaming its discriminator or wire
  name breaks replay of every trace already recorded; removing it makes those
  traces undecodable.
- Which makes admission a one-way door, and it is why the bar below is high.
  "Add it now, tidy it later" is not available.
- Additions *are* cheap in the other direction: a new field with a default
  decodes unchanged on older consumers (`ignoreUnknownKeys = true`). A field you
  are unsure about is better deferred with a named re-admission trigger than
  admitted provisionally — there is no provisional tier, and there will not be
  one.

## The four gates

A proposal must clear all four. A single failure is a decline, not a
negotiation — though a decline is usually a *defer with a trigger*, and naming
that trigger is part of a good proposal.

| # | Gate | The question | What evidence looks like |
| - | ---- | ------------ | ------------------------ |
| 1 | **Noun** | Would two independent apps exchange this thing, under this name? | Two named apps that both model it as a first-class object. A thing only your product has is not a noun; a thing an administrator can rename or delete in a provider's config is not a noun either. |
| 2 | **Intersection** | Can it be modelled as the intersection of ≥2 real providers? | A field-by-field table across the providers, with per-provider *lossy fields* named on day one. If the intersection is one field wide, there is no type here. |
| 3 | **Producer** | Does a plausible Link exist *today* that would produce it? | The transport, named. A noun with no producer is a schema, not canon. |
| 4 | **Bulk** | Does the entity stay free of unbounded payloads? | Schema and counts on the entity; rows, bytes, and prose out of band by reference (`CanonAssetRef` + `AssetResolver`), previews bounded by a write-side factory. |

## The form

Copy from here down.

````markdown
## Proposed noun: <NAME>

**Proposer:** <who, and which product needs it>
**Proposed ring:** <INTERCHANGE | PLATFORM | SERVICE> — and why that ring
**Proposed `wireName` / `@SerialName`:** <snake_case> / `canon.<snake_case>`

### Gate 1 — Noun
Which two independent apps exchange this, and under what name in each. State
whether either name is administrator-configurable.

### Gate 2 — Intersection
| Canon field | <Provider A> | <Provider B> | <Provider C> | Notes |
| ----------- | ------------ | ------------ | ------------ | ----- |
|             |              |              |              | n/3   |

**Dropped per provider (day-one `lossyFields`):**
- **<Provider A>** — …
- **<Provider B>** — …

Call out any field that is a *type* mismatch rather than an omission (a
date-without-zone against `Instant`, a rich-text body against `String`), and say
what the adapter will normalise to.

### Gate 3 — Producer
The Link that would produce this today, and whether it exists in-repo or is
proposed. Evidence read live over that Link is the strongest form.

### Gate 4 — Bulk
The largest realistic instance, and where the unbounded parts go instead.
Name the bound and where it is enforced (write-side factory, not a decode-time
`require` — an oversized value already recorded must stay replayable).

### Proposed field set
```kotlin
// canon.<snake_case>
field: Type, nullableField: Type? = null, …
```

### Deferred fields, with re-admission triggers
Anything you considered and left out, and the provider evidence that would
change the answer.

### Version skew
The minimum Ampere minor a consumer needs to read this noun, and what an older
consumer does when it meets one (a typed skew failure at the envelope — never a
lenient decode).
````

## What happens after acceptance

An accepted proposal is additive and ships in a minor, not a patch:

1. The `CanonType` member, with its `wireName` and `ring`.
2. The `@Serializable` entity with a stable `@SerialName`, its KDoc naming the
   provider intersection and the lossy fields from gate 2.
3. A sample in `CanonSerializationTest.samples()` — the only tripwire; there is
   no exhaustive `when` over `CanonType` or `CanonEntity` anywhere in the repo,
   so nothing else fails at compile time if you forget.
4. A row in `AppleCanonBindingRegistry` (`UNBOUND` is a valid, informative row).
   `AndroidCanonBindingRegistry` derives from `CanonType.entries` and needs no
   edit.
5. Pre-existing samples verified byte-identical, empirically.
6. `docs/concepts/domain-canon.md` updated, and `last_verified` bumped.

## What happens after a decline

The noun stays consumer-side, and that is a supported destination rather than a
dead end:

- A Plug whose observations are entirely outside canon sets
  `PlugManifest.isCanonExternal` and leaves `emits` empty. It may still declare
  `consumes` / `optionalConsumes` for canon it takes *in*.
- `PerceiveSource<out T>` and `ExecuteSink<in C>` are not bound to
  `CanonEntity`, so a consumer-defined type travels the same chassis surface as
  a canon one.
- A canon entity that merely needs one extra provider-specific field does not
  need a new noun at all: `CanonProvenance.nativePayload` carries the lossless
  native object alongside the projection.
- Record the decline with its re-admission trigger, so the next proposer
  inherits the reasoning instead of re-deriving it. The rejections that came
  back repeatedly are pinned in `domain-canon.md`'s *Anti-patterns*.
