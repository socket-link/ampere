package link.socket.ampere.plug.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One constraint in a [PerceiveQuery.predicates] conjunction.
 *
 * ## The vocabulary is closed at three forms
 *
 * [Equals], [Not] and [HasNoRelation] are exactly what the work-source
 * ready-queue needs ("carries `wave:w0`, does not carry
 * `gate:awaiting-verdict`, has no open blocker" — AMPR-289 verdict). This is
 * not the seed of a query language: no disjunction, no ranges, no nesting
 * beyond [Not] over [Equals]. A fourth form needs its own recon-backed
 * ticket and a live consumer, not a speculative one.
 *
 * ## Field and relation names are source-defined
 *
 * [Equals.field] and [HasNoRelation.kind] are strings each [PerceiveSource]
 * documents for itself — a sealed field enum per source would buy nothing
 * while the source is the only interpreter of its own names. A name the
 * source does not recognise is not an error: it is a predicate the source
 * cannot evaluate, and it goes back to the caller in
 * [PerceivePage.residual].
 *
 * Every form is `@Serializable` with a stable `@SerialName` because queries
 * belong in recorded traces — renaming one breaks replay of every trace that
 * recorded it.
 */
@Serializable
sealed interface PerceivePredicate {

    /**
     * The native object's [field] has [value]. For a multi-valued field
     * (labels, tags, recipients) this means "contains [value]".
     */
    @Serializable
    @SerialName("perceive_predicate.equals")
    data class Equals(val field: String, val value: String) : PerceivePredicate

    /**
     * Negation of an [Equals] — "does not carry label X".
     *
     * Typed to [Equals] on purpose. `Not(HasNoRelation)` would be "has a
     * relation" and `Not(Not(x))` double negation; the live consumer needs
     * neither, and admitting them would grow the algebra this type exists
     * to bound.
     */
    @Serializable
    @SerialName("perceive_predicate.not")
    data class Not(val predicate: Equals) : PerceivePredicate

    /**
     * The native object has no relation of [kind] — "has no open blocking
     * issue".
     *
     * There is no qualifier argument: "no *open* blocker" is a distinct
     * source-defined kind (e.g. `"blocked-by-open"`), not
     * `HasNoRelation("blocked-by")` plus a state filter.
     */
    @Serializable
    @SerialName("perceive_predicate.has_no_relation")
    data class HasNoRelation(val kind: String) : PerceivePredicate
}

/**
 * Tests one entity against one leaf [PerceivePredicate] — the knowledge the
 * framework lacks, since it cannot read a field called `"label"` off an
 * arbitrary `T`. Supplied by the source (which owns its field names) or the
 * caller, and consumed by [applyResidual].
 *
 * One method per leaf form, so an implementation is exhaustive by type;
 * [PerceivePredicate.Not] and the conjunction are handled by the framework.
 * Both are `suspend` because answering can be I/O — checking
 * [PerceivePredicate.HasNoRelation] on an issue tracker means reading each
 * candidate's relations.
 */
interface PredicateEvaluator<in T> {

    suspend fun matches(entity: T, predicate: PerceivePredicate.Equals): Boolean

    suspend fun hasNoRelation(entity: T, predicate: PerceivePredicate.HasNoRelation): Boolean
}

/**
 * Apply this page's [PerceivePage.residual] predicates client-side, returning
 * an exact page: only the entities that satisfy every residual predicate,
 * with those predicates moved into [PerceivePage.evaluated].
 *
 * This is the standard way to consume an over-approximate page. The result
 * may hold fewer entities than the source's page size — the same "a source
 * may return fewer" allowance [PerceiveQuery.limit] already makes — and
 * [PerceivePage.nextCursor] and [PerceivePage.partialFailures] carry over
 * unchanged. An already-exact page is returned as-is, without consulting
 * [evaluator].
 */
suspend fun <T> PerceivePage<T>.applyResidual(evaluator: PredicateEvaluator<T>): PerceivePage<T> {
    if (isExact) return this
    val kept = entities.filter { entity -> residual.all { evaluator.test(entity, it) } }
    return copy(
        entities = kept,
        evaluated = evaluated + residual,
        residual = emptyList(),
    )
}

private suspend fun <T> PredicateEvaluator<T>.test(entity: T, predicate: PerceivePredicate): Boolean =
    when (predicate) {
        is PerceivePredicate.Equals -> matches(entity, predicate)
        is PerceivePredicate.Not -> !matches(entity, predicate.predicate)
        is PerceivePredicate.HasNoRelation -> hasNoRelation(entity, predicate)
    }
