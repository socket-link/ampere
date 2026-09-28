package link.socket.ampere.lifecycle

import kotlin.jvm.JvmInline
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProse
import link.socket.ampere.probe.Observed

/** Identity of a [ReconFinding] within the unit of work that filed it, e.g. `R5-3`. Opaque. */
@JvmInline
@Serializable
value class ReconFindingId(val value: String)

/**
 * A claim a recon made, the evidence for it, and a label saying how the claim
 * was arrived at.
 *
 * This is the record a recon hands to a human-judgment gate: "`Finding` is
 * already taken — **verified**, read at `QualityState.kt:226`", as opposed to
 * "the rename is probably safe — **inferred**". The label is what lets a
 * reader decide how much of a decision to rest on the claim, and it is the one
 * thing neither of this type's two neighbours carries.
 *
 * ## Why this is not `qa.Finding`
 *
 * `link.socket.ampere.agents.definition.qa.Finding` is a **code-quality
 * defect**: a message, a [severity][link.socket.ampere.agents.definition.qa.FindingSeverity],
 * a location, a suggested fix. It answers *what is wrong with this code and
 * how bad is it*. It has no evidence reference and no confidence label,
 * because a defect a validator found is not a claim anyone needs to weigh — it
 * is a thing to fix. A recon finding is frequently not a defect at all
 * ("no decision register of any shape exists"), has no severity, and is
 * useless without its provenance. The two share a word and nothing else, which
 * is why this type carries the `Recon` prefix rather than a second `Finding`
 * in the same artifact.
 *
 * ## Why this is not `Verdict`, and not a dimension added to it
 *
 * [link.socket.ampere.probe.Verdict] was the genuine fork: it is the closest
 * relative, and `Undetermined(EVIDENCE_ABSENT)` looks at first like the
 * *untested* label. It was rejected as the home for three reasons.
 *
 * - **They answer different questions.** A `Verdict` is the outcome of a
 *   `Probe` — *does this predicate hold over the evidence I can see*. A
 *   finding is an assertion about the world — *this is so, and here is how I
 *   know*. A finding has no predicate and no subject type; a verdict has no
 *   claim text.
 * - **The values do not map.** [ReconConfidence.UNTESTED] means nobody looked.
 *   `Undetermined(EVIDENCE_ABSENT)` means a check *ran* and the evidence was
 *   not there — a stronger statement, with a remedy attached.
 *   [ReconConfidence.INFERRED] has no counterpart at all, and cannot be given
 *   one: `Verdict` is convict-but-not-acquit, and an inferred claim is exactly
 *   the soft pass `Undetermined` exists to forbid.
 * - **A provenance field on `Verdict` would be dead weight on every Probe.** A
 *   Probe evaluates synchronously over evidence it holds, so every verdict it
 *   reaches is first-hand by construction; the field would read `verified` on
 *   all four variants forever, while changing the wire shape of every recorded
 *   `ProbeReport`.
 *
 * The two compose rather than merge. [ReconEvidence] is [Observed], so a
 * `FreshnessProbe` runs over a finding's evidence and returns a `Verdict`
 * about it — a Probe can *test* a finding; it cannot *be* one.
 *
 * ## Not a canon entity
 *
 * No provider ships a recon finding, so it clears none of the canon's
 * admission gates and is deliberately outside `CanonType`. It points *at*
 * canon — [subject] is a [CanonId] — without being a member of it.
 *
 * **No `init` validation**, for the reason `CanonWorkGraph` gives: a finding
 * labelled [ReconConfidence.VERIFIED] with nothing behind it must be
 * constructible and decodable, so it can be recorded and then caught.
 * [isSubstantiated] exposes the rule.
 *
 * @property id Identity within the filing unit of work.
 * @property claim The assertion, in prose. One claim per finding — a finding
 *   that needs two labels is two findings.
 * @property confidence How the claim was arrived at. A label, not a score:
 *   there is no threshold to tune and no arithmetic to do on it.
 * @property evidence What was read, in the order it was read. Empty is legal
 *   for [ReconConfidence.INFERRED] and [ReconConfidence.UNTESTED].
 * @property subject The unit of work the finding was filed against, or null.
 *   A same-Link [CanonId] under the cross-reference contract in
 *   `docs/concepts/domain-canon.md`: null does not mean "about nothing", and
 *   nothing guarantees the referent was ever perceived.
 */
@Serializable
@SerialName("lifecycle.recon_finding")
data class ReconFinding(
    val id: ReconFindingId,
    val claim: String,
    val confidence: ReconConfidence,
    val evidence: List<ReconEvidence> = emptyList(),
    val subject: CanonId? = null,
) {

    /**
     * Whether the label is backed by what it claims to be backed by: false only
     * for a [ReconConfidence.VERIFIED] finding that names no [evidence].
     *
     * It does not check that the evidence *supports* the claim — that is a
     * judgment, and the reason a person sits at the gate.
     */
    val isSubstantiated: Boolean
        get() = confidence != ReconConfidence.VERIFIED || evidence.isNotEmpty()
}

/**
 * How a [ReconFinding]'s claim was arrived at.
 *
 * Three values, and deliberately not a number. A reader needs to know whether
 * somebody looked, not how sure they felt.
 */
@Serializable
enum class ReconConfidence {

    /**
     * Read first-hand from a primary source or a read symbol — the file, the
     * commit, the provider's own record. The only label that obliges the
     * finding to name [ReconFinding.evidence].
     */
    @SerialName("verified")
    VERIFIED,

    /**
     * Reasoned from something else that was read, without the claim itself
     * being observed. "Nothing imports it, so removing it is safe" is inferred
     * until the build says so.
     */
    @SerialName("inferred")
    INFERRED,

    /**
     * Asserted without being checked. Not a failed check — no check was run.
     * That is the whole distinction from `Verdict.Undetermined`.
     */
    @SerialName("untested")
    UNTESTED,
}

/**
 * A reference to something a recon read — never the thing itself.
 *
 * The bulk rule the canon applies to tables and prose applies here for the
 * same reason: a finding that carried the file it cites would put a repository
 * in a trace. [excerpt] is a bounded quotation; the source stays where it is.
 *
 * Implements [Observed], so the age of a finding's evidence is a
 * `FreshnessProbe` question and not a field on this type — a finding verified
 * at a commit from March is a different thing in June, and how different is
 * the consumer's policy.
 *
 * @property source What was read: a repository path, a URL, a ticket. Opaque
 *   in the way `SourceHandle.nativeId` is — used for display and for a person
 *   following it, never parsed.
 * @property observedAt When it was read. Bound once, by whoever read it, and
 *   never re-stamped when the finding is filed, copied, or gated.
 * @property revision What pins [source] to the state that was read: a commit
 *   SHA, an etag, a document version. Null when the source has no such notion,
 *   in which case [observedAt] is the only pin there is.
 * @property locator Where within [source]: a line range, a symbol, a heading.
 * @property excerpt The words that were read, bounded by [CanonProse.bounded].
 */
@Serializable
@SerialName("lifecycle.recon_evidence")
data class ReconEvidence(
    val source: String,
    override val observedAt: Instant,
    val revision: String? = null,
    val locator: String? = null,
    val excerpt: CanonProse? = null,
) : Observed
