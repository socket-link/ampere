package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.WorkflowId
import link.socket.ampere.agents.domain.event.EventId

/**
 * Where this [Emission] came from. Every Emission carries provenance — that
 * is what makes it auditable downstream.
 *
 *  - `runId` / `workflowId` link the Emission to the Arc and reasoning unit
 *    that produced it.
 *  - `parentEmissionId` is the causal edge: the Emission this one was
 *    produced in service of, or `null` for a root. Walking these edges is
 *    how "everything this caused" is found, as data rather than by
 *    inference over bus ordering.
 *  - `principal` records on whose authority the Emission was produced. What
 *    a principal means is D5's to decide; see [Principal].
 *  - `sourceEventId` records the event that motivated the Emission (for
 *    example, the tool result that prompted a Confirmation). It points at an
 *    *event*, never at a parent Emission; that is what `parentEmissionId`
 *    is for.
 *  - `toolInvocationId`, `plugId`, `modelId` are populated when the
 *    Emission can be attributed to a specific tool call, plug, or model.
 *  - `inputDigest` is the deterministic SHA-256 hash of the payload (see
 *    [inputDigest]) and lets consumers reason about content identity
 *    without re-hashing.
 *
 * `parentEmissionId` and `principal` have no defaults on purpose. Every
 * producer states both, so a `null` parent always means "this is a root" and
 * never "nobody passed it".
 *
 * On the wire both keys are always written. Payloads from before the edge
 * existed carry neither, and decode as a root under [Principal.Ambient]. That
 * is an accurate account of them: no Emission recorded a parent then, and
 * nothing ran under anything but ambient authority. Because new payloads
 * always carry both keys, a reader of the raw JSON can still tell a root from
 * a payload written before the edge.
 */
@Serializable(with = EmissionProvenanceSerializer::class)
data class EmissionProvenance(
    val runId: RunId? = null,
    val workflowId: WorkflowId? = null,
    val sourceEventId: EventId? = null,
    val toolInvocationId: String? = null,
    val plugId: String? = null,
    val modelId: String? = null,
    val inputDigest: String,
    // Appended rather than grouped with runId: every neighbour is a String?, so inserting
    // these mid-list would silently shift componentN() for anyone destructuring a provenance.
    val parentEmissionId: EmissionId?,
    val principal: Principal,
)

/**
 * The wire shape of [EmissionProvenance]. It has the same fields and serial name, but gives
 * the causal edge and the principal the decode-side defaults for payloads written before they
 * existed. It also forces both to be encoded, whatever `encodeDefaults` the caller's `Json` uses.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("link.socket.ampere.agents.domain.emission.EmissionProvenance")
private class EmissionProvenanceSurrogate(
    val runId: RunId? = null,
    val workflowId: WorkflowId? = null,
    val sourceEventId: EventId? = null,
    val toolInvocationId: String? = null,
    val plugId: String? = null,
    val modelId: String? = null,
    val inputDigest: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val parentEmissionId: EmissionId? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val principal: Principal = Principal.Ambient,
)

internal object EmissionProvenanceSerializer : KSerializer<EmissionProvenance> {

    override val descriptor: SerialDescriptor = EmissionProvenanceSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EmissionProvenance) {
        encoder.encodeSerializableValue(
            EmissionProvenanceSurrogate.serializer(),
            EmissionProvenanceSurrogate(
                runId = value.runId,
                workflowId = value.workflowId,
                sourceEventId = value.sourceEventId,
                toolInvocationId = value.toolInvocationId,
                plugId = value.plugId,
                modelId = value.modelId,
                inputDigest = value.inputDigest,
                parentEmissionId = value.parentEmissionId,
                principal = value.principal,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): EmissionProvenance {
        val surrogate = decoder.decodeSerializableValue(EmissionProvenanceSurrogate.serializer())
        return EmissionProvenance(
            runId = surrogate.runId,
            workflowId = surrogate.workflowId,
            sourceEventId = surrogate.sourceEventId,
            toolInvocationId = surrogate.toolInvocationId,
            plugId = surrogate.plugId,
            modelId = surrogate.modelId,
            inputDigest = surrogate.inputDigest,
            parentEmissionId = surrogate.parentEmissionId,
            principal = surrogate.principal,
        )
    }
}
