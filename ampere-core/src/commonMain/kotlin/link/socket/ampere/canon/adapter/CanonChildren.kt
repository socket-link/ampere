package link.socket.ampere.canon.adapter

import link.socket.ampere.canon.CanonProvenance

/*
 * Identity and provenance rules for canon entities extracted from *inside* another one.
 *
 * Several canon types nest others: a `CanonCalendarEvent` carries a `CanonPlace` and a list of
 * `CanonPerson`; an album carries assets; a folder carries files. Each nested entity needs its own
 * `CanonId` and `CanonProvenance`, and neither is available from the native payload — the adapter
 * has to synthesise both. Two ways of doing that are wrong, and both are easy to reach for:
 *
 *  - Deriving a child id from its position in a collection. `"$eventId:attendee:0"` changes the
 *    moment the provider reorders the array, so anything that cached or referenced the child is
 *    silently pointing at a different entity.
 *  - Reusing the parent's provenance verbatim. The parent's provenance carries the parent's
 *    `nativeId` and the parent's `nativePayload`, so a person extracted from a calendar event ends
 *    up claiming to *be* an `EKEvent`. Write-back through a Contacts adapter would then fail the
 *    schema check — safe, but only by accident.
 *
 * These helpers make the correct construction the short one: `childNativeId` for the identifier (fed
 * to both `CanonId` and `forChild`, so the two cannot disagree), `forChild` for provenance, and a
 * natural key rather than an index wherever the provider offers one.
 */

/**
 * Provenance for an entity extracted from inside the entity this provenance belongs to.
 *
 * Retargets [CanonProvenance.sourceHandle] at the child's own native identifier and drops the
 * parent's [CanonProvenance.nativePayload]. Dropping the payload is deliberate: the payload is
 * what makes write-back a pure merge, and a child is *not* independently writable through its
 * parent's adapter — carrying the parent's payload would advertise a write path that cannot
 * work. A child that needs write-back gets it from the adapter that owns its own schema.
 *
 * [CanonProvenance.observedAt] is preserved, because the child was observed at the same moment
 * as its parent. The parent's `etag` is dropped for the same reason as the payload: it is an
 * optimistic-concurrency token for the parent object, and applying it to a child would let a
 * stale-check pass on the wrong record.
 */
fun CanonProvenance.forChild(childNativeId: String): CanonProvenance =
    copy(
        sourceHandle = sourceHandle.copy(nativeId = childNativeId, etag = null),
        nativePayload = null,
    )

/**
 * A stable native identifier for a child entity, qualified by the parent it came from.
 *
 * @param parentNativeId The containing entity's native id — scopes the child so two events
 *   with an attendee of the same address don't collide into one canon identity.
 * @param role What the child is to its parent, e.g. `"attendee"`, `"place"`. Keeps two child
 *   collections on the same parent from colliding.
 * @param naturalKey A value the *provider* considers identifying — an email address, a file
 *   path, an asset id. **Never a collection index.** Where a source genuinely offers no natural
 *   key, call [unstableChildNativeId] instead, so the instability is visible at the call site
 *   rather than hidden in a string template.
 */
fun childNativeId(
    parentNativeId: String,
    role: String,
    naturalKey: String,
): String = "$parentNativeId:$role:$naturalKey"

/**
 * A child identifier for a source that offers no natural key.
 *
 * Positionally derived and therefore **not stable across reordering**. Named to say so: an
 * adapter reaching for this is accepting that the child's identity may change between two
 * observations of an unchanged parent. Prefer [childNativeId]; use this only when the provider
 * exposes nothing identifying, and say so in the adapter's KDoc.
 */
fun unstableChildNativeId(
    parentNativeId: String,
    role: String,
    index: Int,
): String = "$parentNativeId:$role:#$index"
