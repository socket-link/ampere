package link.socket.ampere.field.observe

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonType
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.link.LinkId

/*
 * Package `link.socket.ampere.field.observe` — how a canon entity enters the Field (F7).
 *
 * The canon is the Field's Environment branch, so nothing here re-declares an Environment
 * type: a perceived `CanonEntity` stays the Environment state, and what enters the Field is an
 * *Observation* — a `Belief` held by the perceiving Subject, about the entity, whose provenance
 * is the perceive event that observed it. This package maps; it does not store. Writing
 * Observations is the fold's job.
 *
 * Still a leaf: canon imports nothing from `agents.domain`, so depending on it here keeps
 * `FieldBoundaryTest` green.
 */

/**
 * Confidence an Observation is held at: the holder witnessed the source report the entity, and
 * that much is not in doubt. Whether the world still agrees is a question of age — [Decay], or a
 * `FreshnessProbe` over `observedAt` — not of confidence. No F10 mapping applies: an observation
 * is a read, not a model's self-reported level.
 */
const val OBSERVATION_CONFIDENCE: Double = 1.0

/**
 * The [Sensitivity] an Observation of [canonType] defaults to.
 *
 * - [CanonType.PERSON] is [Sensitivity.PROTECTED]: a belief about a real person (F12), so it
 *   leaves no Subject's view but the holder's and is never judged off-device.
 * - Everything else is [Sensitivity.PRIVATE]: perceived over the Principal's own consented Link,
 *   so the holder's view by default. Widening to [Sensitivity.PUBLIC] is a caller's decision,
 *   never a default.
 */
fun defaultObservationSensitivity(canonType: CanonType): Sensitivity =
    if (canonType == CanonType.PERSON) Sensitivity.PROTECTED else Sensitivity.PRIVATE

/**
 * The Field's handle for the canon entity [canonId] perceived over [linkId].
 *
 * Link-qualified because a [CanonId] is not globally unique: the same id from two Links names
 * two entities (the canon's same-Link rule), and a bare-id ref would merge their Observations
 * into one subject. The value is `canon/<linkId>/<canonId>` with `%` and `/` in the Link id
 * percent-escaped, so the first unescaped `/` after the prefix always ends the Link id and no
 * two (Link, id) pairs share a value. Opaque like every [Ref]: build it here, never parse it.
 */
fun canonEnvironmentRef(linkId: LinkId, canonId: CanonId): Ref<Environment> {
    val escapedLink = linkId.value.replace("%", "%25").replace("/", "%2F")
    return Ref("canon/$escapedLink/${canonId.value}")
}

/** This entity's Field handle; see [canonEnvironmentRef]. */
val CanonEntity.environmentRef: Ref<Environment>
    get() = canonEnvironmentRef(provenance.sourceHandle.linkId, canonId)

/**
 * This entity as an Observation held by [holder]: a [Belief.Settled] of kind
 * [BeliefKind.OBSERVATION] about [environmentRef], whose [Provenance.createdBy] is the entity's
 * `CanonProvenance.perceiveEventId`.
 *
 * Fails with [UnrecordedObservationException] when the entity carries no perceive event id —
 * projected outside a recorded perceive, or recorded before F7. Every Belief points at the event
 * that produced it (F11), and an entity that names none cannot honestly be given one.
 *
 * The content is a one-line statement of the observation, not the entity: the entity *is* the
 * Environment state the ref names, and copying its fields into prose would put a perceived
 * person's details somewhere [sensitivity] was not chosen for.
 *
 * @param holder The perceiving Subject — the agent whose door recorded the perceive.
 * @param recordedAt The perceive event's envelope `recorded_at`; see [Provenance.createdAt].
 */
fun CanonEntity.toObservation(
    holder: Ref<Subject>,
    recordedAt: Instant,
    confidence: Double = OBSERVATION_CONFIDENCE,
    sensitivity: Sensitivity = defaultObservationSensitivity(canonType),
    decay: Decay = Decay.None,
): Result<Belief.Settled> {
    val perceiveEventId = provenance.perceiveEventId
        ?: return Result.failure(UnrecordedObservationException(canonId, canonType))

    return Result.success(
        Belief.Settled(
            holder = holder,
            subject = environmentRef,
            content = "Observed ${canonType.wireName} ${canonId.value} via " +
                "${provenance.sourceHandle.sourceSystem} at ${provenance.observedAt}",
            kind = BeliefKind.OBSERVATION,
            confidence = confidence,
            sensitivity = sensitivity,
            decay = decay,
            provenance = Provenance(createdBy = perceiveEventId, createdAt = recordedAt),
        ),
    )
}

/** A canon entity with no `perceiveEventId` cannot enter the Field: it names no event. */
class UnrecordedObservationException(
    val canonId: CanonId,
    val canonType: CanonType,
) : IllegalStateException(
    "${canonType.wireName} ${canonId.value} carries no perceiveEventId; " +
        "only an entity from a recorded perceive enters the Field",
)
