package link.socket.ampere.field

import kotlin.jvm.JvmInline

/**
 * How much a candidate [Action] is pulled toward being taken — goal weight, predicted
 * value, urgency of what it serves.
 *
 * Electrical by decision (F27): `Potential`, not `Score`, in the family of Transformer,
 * Cell, Switch, Relay and Breaker. The metaphor is load-bearing in the ranking model,
 * so the name is fixed before the arithmetic is.
 *
 * A name over a `Double` and nothing more. It is a *signal*: derived by the fold from
 * Beliefs and Constraints at read time, never stored, and never a member of [Action].
 * How it combines with [Resistance] — difference or ratio, depth-derived or
 * Principal-set goal weight — is F28, open and settled empirically in `ampere-eval`;
 * no operator is declared here so that nothing in `ampere-core` can bake the answer
 * in first.
 */
@JvmInline
value class Potential(val value: Double)

/**
 * How much a candidate [Action] is held back — soft Constraints breached, cost,
 * low prediction confidence against its reversibility.
 *
 * Electrical by decision (F27): `Resistance`, not `Penalty`. As with [Potential], a
 * name over a `Double` and a derived signal only; the ranking arithmetic is F28 and
 * is not declared in this package.
 */
@JvmInline
value class Resistance(val value: Double)
