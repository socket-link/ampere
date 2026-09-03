package link.socket.ampere.probe

import kotlinx.datetime.Instant

/**
 * Anything that knows when it was last observed from its source.
 *
 * [link.socket.ampere.canon.CanonProvenance] implements it for canon
 * entities. Consumers implement it on their own binding types for
 * canon-external observations — a Socket `ManifestLine` copies
 * `WebPage.fetchedAt` here at bind time — so a `Probe<Observed>` runs over
 * either without Ampere ever learning the concrete type. The Probe's subject
 * is the Recall *binding*, not the observation.
 *
 * Contract 3: [observedAt] is bound at the earliest moment that can know it —
 * the relay's clock at upstream-response completion for a fetch, the
 * framework's clock at perceive for a Plug — and is never re-stamped on
 * receipt, cache hit, or Plan. The tolerance for how old an observation may be
 * lives on the consumer's Probe ([FreshnessProbe.maxAge]), never on the fact.
 */
interface Observed {
    val observedAt: Instant
}
