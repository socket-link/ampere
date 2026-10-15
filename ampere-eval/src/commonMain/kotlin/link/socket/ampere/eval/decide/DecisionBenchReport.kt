package link.socket.ampere.eval.decide

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What a [DecisionBench] run found, per arm (AMPR-422).
 *
 * The two errors it counts are not equal, and the report keeps them apart everywhere:
 *
 * - **false met** — an unmet case the arm called met. The costly one: a run that believed
 *   it would stop, LEARN would store a success that was not one, and a person would be told
 *   it was done.
 * - **false not met** — a met case the arm called unmet. Costs a cycle or an escalation.
 *
 * A call that returned no answer (the transport threw, or the answer was malformed) counts as
 * **not met** in every confusion count — a run cannot stop on an answer it never got — and is
 * counted separately as `missing`, so a transport that fails a tenth of its calls shows up as
 * a transport that fails a tenth of its calls.
 *
 * ### Threshold sweep and the zero-false-met operating point
 *
 * Only for an arm whose every answer `BandFitGuard` would accept — measured, with a
 * distribution. A self-reported arm gets no sweep: its `confidence` is the F10 mapping of a
 * word the model said about itself, and sweeping that would fit a band on a self-report (J5).
 *
 * `P(met)` is the distribution's mass on [positiveKeys]. The sweep reads the arm's calls at 21
 * thresholds, `0.00, 0.05 … 1.00`, calling met when `P(met) ≥ t`. The **zero-false-met
 * operating point** is the loosest threshold at which no unmet case is called met: the
 * largest `P(met)` any unmet sample reached, with the rule `met when P(met) > threshold`, and
 * beside it the share of met samples still recognised there. It is computed twice — on the
 * mean of each case's repeats, and on every single call as its own sample — because a run
 * asks once and a bench asks three times.
 *
 * @property cases The cases asked.
 * @property armIds The arms asked, in run order.
 * @property repeats How many times each case was asked of each arm.
 * @property records Every answer, one per case, arm and repeat.
 * @property positiveKeys The answer keys read as "met".
 */
class DecisionBenchReport(
    val cases: List<DecisionCase>,
    val armIds: List<String>,
    val repeats: Int,
    val records: List<DecisionRecord>,
    val positiveKeys: Set<String>,
) {
    private val casesById: Map<String, DecisionCase> = cases.associateBy { it.id }
    private val recordsByArm: Map<String, List<DecisionRecord>> = records.groupBy { it.armId }

    init {
        require(positiveKeys.isNotEmpty()) { "A report needs at least one key that means 'met'" }
        records.forEach { record ->
            require(record.caseId in casesById) { "Record for unknown case '${record.caseId}'" }
        }
    }

    /** The counts a confusion matrix holds, with the two error rates read off them. */
    data class Confusion(
        val metCalledMet: Int,
        val falseNotMet: Int,
        val falseMet: Int,
        val unmetCalledUnmet: Int,
        val missing: Int,
    ) {
        val metCases: Int get() = metCalledMet + falseNotMet
        val unmetCases: Int get() = falseMet + unmetCalledUnmet
        val total: Int get() = metCases + unmetCases

        /** Share of unmet calls the arm called met. Null when there were no unmet calls. */
        val falseMetRate: Double? get() = unmetCases.takeIf { it > 0 }?.let { falseMet.toDouble() / it }

        /** Share of met calls the arm called unmet. Null when there were no met calls. */
        val falseNotMetRate: Double? get() = metCases.takeIf { it > 0 }?.let { falseNotMet.toDouble() / it }

        /** Share of all calls answered correctly. */
        val accuracy: Double? get() = total.takeIf { it > 0 }?.let { (metCalledMet + unmetCalledUnmet).toDouble() / it }

        operator fun plus(other: Confusion): Confusion = Confusion(
            metCalledMet = metCalledMet + other.metCalledMet,
            falseNotMet = falseNotMet + other.falseNotMet,
            falseMet = falseMet + other.falseMet,
            unmetCalledUnmet = unmetCalledUnmet + other.unmetCalledUnmet,
            missing = missing + other.missing,
        )

        companion object {
            val EMPTY = Confusion(0, 0, 0, 0, 0)
        }
    }

    /** One row of a threshold sweep: the two error counts when met means `P(met) ≥ threshold`. */
    data class SweepPoint(
        val threshold: Double,
        val falseMet: Int,
        val falseNotMet: Int,
        val unmetSamples: Int,
        val metSamples: Int,
    ) {
        val falseMetRate: Double? get() = unmetSamples.takeIf { it > 0 }?.let { falseMet.toDouble() / it }
        val falseNotMetRate: Double? get() = metSamples.takeIf { it > 0 }?.let { falseNotMet.toDouble() / it }
    }

    /**
     * The loosest threshold with no false met.
     *
     * @property threshold The largest `P(met)` any unmet sample reached. Met is `P(met) > threshold`.
     * @property metRecognised Share of met samples with `P(met)` above it — what the arm still
     *   recognises as done once it is forbidden to be wrong the costly way.
     * @property metSamples How many met samples that share is over.
     * @property unmetSamples How many unmet samples set the threshold.
     */
    data class OperatingPoint(
        val threshold: Double,
        val metRecognised: Double,
        val metSamples: Int,
        val unmetSamples: Int,
    )

    /** How a sample is formed for the sweep: one per case (its repeats averaged) or one per call. */
    enum class Aggregate { MeanOfRepeats, SingleCall }

    /** Share of cases on which every repeat gave the same answer. */
    data class Stability(val agreeingCases: Int, val casesWithAllRepeats: Int) {
        val agreement: Double? get() = casesWithAllRepeats.takeIf { it > 0 }?.let { agreeingCases.toDouble() / it }
    }

    /** Latency percentiles over answered calls, nearest rank. */
    data class Latency(val p50Ms: Long, val p95Ms: Long, val samples: Int)

    /** Token and cost totals over answered calls; null sums mean no call reported the field. */
    data class Usage(
        val calls: Int,
        val callsReportingTokens: Int,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val reportedCostUsd: Double?,
    )

    /** The records of [armId], in run order. Empty for an arm the run never asked. */
    fun recordsOf(armId: String): List<DecisionRecord> = recordsByArm[armId].orEmpty()

    /** True when [case]'s label is one of [positiveKeys]. */
    fun isMet(case: DecisionCase): Boolean = case.expected in positiveKeys

    /** True when [record]'s answer is one of [positiveKeys]; a missing answer is not. */
    fun calledMet(record: DecisionRecord): Boolean = record.answer in positiveKeys

    /** `P(met)` for [record], or null when it has no distribution. */
    fun pMet(record: DecisionRecord): Double? = record.distribution?.let { distribution ->
        positiveKeys.sumOf { distribution[it] ?: 0.0 }
    }

    /** The confusion counts of [armId] over the cases [include] accepts. */
    fun confusion(armId: String, include: (DecisionCase) -> Boolean = { true }): Confusion {
        var metCalledMet = 0
        var falseNotMet = 0
        var falseMet = 0
        var unmetCalledUnmet = 0
        var missing = 0
        for (record in recordsOf(armId)) {
            val case = casesById.getValue(record.caseId)
            if (!include(case)) continue
            if (!record.answered) missing++
            when {
                isMet(case) && calledMet(record) -> metCalledMet++
                isMet(case) -> falseNotMet++
                calledMet(record) -> falseMet++
                else -> unmetCalledUnmet++
            }
        }
        return Confusion(metCalledMet, falseNotMet, falseMet, unmetCalledUnmet, missing)
    }

    /** [confusion] per case kind, in the order kinds first appear in [cases]. */
    fun byKind(armId: String): Map<String, Confusion> =
        cases.map { it.kind }.distinct().associateWith { kind -> confusion(armId) { it.kind == kind } }

    /** [confusion] per case family, in first-appearance order; empty when no case names one. */
    fun byFamily(armId: String): Map<String, Confusion> =
        cases.mapNotNull { it.family }.distinct().associateWith { family -> confusion(armId) { it.family == family } }

    /**
     * The kind [armId] gets wrong most often, by share of its calls answered wrongly; ties go
     * to the kind listed first. Null when the arm answered nothing.
     */
    fun weakestKind(armId: String): Pair<String, Double>? = byKind(armId)
        .mapNotNull { (kind, confusion) ->
            confusion.accuracy?.let { kind to (1.0 - it) }
        }
        .maxByOrNull { it.second }

    /**
     * True when every answered call of [armId] is one `BandFitGuard` accepts. False for an arm
     * with no answered calls, or any self-reported or distribution-less one.
     */
    fun isFittable(armId: String): Boolean {
        val answered = recordsOf(armId).filter { it.answered }
        return answered.isNotEmpty() && answered.all { it.fittable }
    }

    /** The 21-point sweep for [armId], or null when the arm is not [isFittable]. */
    fun sweep(armId: String, aggregate: Aggregate = Aggregate.SingleCall): List<SweepPoint>? {
        val samples = samples(armId, aggregate) ?: return null
        val met = samples.filter { it.met }
        val unmet = samples.filter { !it.met }
        return (0..SWEEP_STEPS).map { step ->
            val threshold = step.toDouble() / SWEEP_STEPS
            SweepPoint(
                threshold = threshold,
                falseMet = unmet.count { it.pMet >= threshold - EPSILON },
                falseNotMet = met.count { it.pMet < threshold - EPSILON },
                unmetSamples = unmet.size,
                metSamples = met.size,
            )
        }
    }

    /**
     * The zero-false-met operating point for [armId], or null when the arm is not
     * [isFittable] or has no unmet sample to set the threshold.
     */
    fun zeroFalseMet(armId: String, aggregate: Aggregate): OperatingPoint? {
        val samples = samples(armId, aggregate) ?: return null
        val unmet = samples.filter { !it.met }
        val met = samples.filter { it.met }
        if (unmet.isEmpty()) return null
        val threshold = unmet.maxOf { it.pMet }
        val recognised = met.count { it.pMet > threshold + EPSILON }
        return OperatingPoint(
            threshold = threshold,
            metRecognised = if (met.isEmpty()) 0.0 else recognised.toDouble() / met.size,
            metSamples = met.size,
            unmetSamples = unmet.size,
        )
    }

    /** How often [armId] gave the same answer to a case on every repeat. */
    fun stability(armId: String): Stability {
        val byCase = recordsOf(armId).groupBy { it.caseId }.filterValues { it.size == repeats }
        val agreeing = byCase.values.count { group -> group.map { it.answer }.distinct().size == 1 }
        return Stability(agreeingCases = agreeing, casesWithAllRepeats = byCase.size)
    }

    /** Latency of [armId]'s answered calls, or null when none answered. */
    fun latency(armId: String): Latency? {
        val sorted = recordsOf(armId).filter { it.answered }.map { it.latencyMs }.sorted()
        if (sorted.isEmpty()) return null
        return Latency(p50Ms = sorted.nearestRank(0.50), p95Ms = sorted.nearestRank(0.95), samples = sorted.size)
    }

    /** Usage totals over [armId]'s answered calls. */
    fun usage(armId: String): Usage {
        val answered = recordsOf(armId).filter { it.answered }
        val withTokens = answered.filter { it.usage.inputTokens != null || it.usage.outputTokens != null }
        val withCost = answered.mapNotNull { it.usage.estimatedCost }
        return Usage(
            calls = answered.size,
            callsReportingTokens = withTokens.size,
            inputTokens = withTokens.takeIf { it.isNotEmpty() }?.sumOf { (it.usage.inputTokens ?: 0).toLong() },
            outputTokens = withTokens.takeIf { it.isNotEmpty() }?.sumOf { (it.usage.outputTokens ?: 0).toLong() },
            reportedCostUsd = withCost.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    /** The calls of [armId] that returned no usable answer, with their errors. */
    fun missing(armId: String): List<DecisionRecord> = recordsOf(armId).filter { !it.answered }

    private class Sample(val met: Boolean, val pMet: Double)

    private fun samples(armId: String, aggregate: Aggregate): List<Sample>? {
        if (!isFittable(armId)) return null
        val answered = recordsOf(armId).filter { it.answered }
        return when (aggregate) {
            Aggregate.SingleCall -> answered.map { record ->
                Sample(met = isMet(casesById.getValue(record.caseId)), pMet = pMet(record) ?: 0.0)
            }
            Aggregate.MeanOfRepeats -> answered.groupBy { it.caseId }.map { (caseId, group) ->
                Sample(
                    met = isMet(casesById.getValue(caseId)),
                    pMet = group.map { pMet(it) ?: 0.0 }.average(),
                )
            }
        }
    }

    /**
     * The report as Markdown: one section per arm with its confusion counts, operating points,
     * sweep, per-kind and per-family tables, stability, latency and usage. What the doc and the
     * ticket comment are built from, so the numbers there are the numbers here.
     */
    fun render(title: String = "Decision bench report"): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("Cases: ${cases.size} (${cases.count(::isMet)} met, ${cases.count { !isMet(it) }} unmet). ")
        appendLine("Repeats: $repeats. Met keys: ${positiveKeys.joinToString()}.")
        appendLine()
        for (armId in armIds) {
            renderArm(armId)
        }
    }

    private fun StringBuilder.renderArm(armId: String) {
        val all = confusion(armId)
        appendLine("## Arm `$armId`")
        appendLine()
        if (recordsOf(armId).isEmpty()) {
            appendLine("_No records._")
            appendLine()
            return
        }
        appendLine("| Calls | False met | False not met | Met called met | Unmet called unmet | Missing |")
        appendLine("| -- | -- | -- | -- | -- | -- |")
        appendLine(
            "| ${all.total} | ${all.falseMet} (${pct(all.falseMetRate)}) | " +
                "${all.falseNotMet} (${pct(all.falseNotMetRate)}) | ${all.metCalledMet} | " +
                "${all.unmetCalledUnmet} | ${all.missing} |",
        )
        appendLine()
        weakestKind(armId)?.let { (kind, errorRate) ->
            appendLine("Weakest kind: `$kind` (${pct(errorRate)} of its calls wrong).")
            appendLine()
        }
        if (isFittable(armId)) {
            appendLine("### Zero-false-met operating point")
            appendLine()
            appendLine("| Samples | Threshold (met when P(met) > t) | Met recognised | Met samples | Unmet samples |")
            appendLine("| -- | -- | -- | -- | -- |")
            for (aggregate in Aggregate.entries) {
                val point = zeroFalseMet(armId, aggregate) ?: continue
                appendLine(
                    "| ${aggregate.label()} | ${point.threshold.fmt()} | ${pct(point.metRecognised)} | " +
                        "${point.metSamples} | ${point.unmetSamples} |",
                )
            }
            appendLine()
            appendLine("### Threshold sweep (single calls; met when P(met) ≥ t)")
            appendLine()
            appendLine("| t | False met | False not met |")
            appendLine("| -- | -- | -- |")
            sweep(armId)?.forEach { point ->
                appendLine(
                    "| ${point.threshold.fmt()} | ${point.falseMet} (${pct(point.falseMetRate)}) | " +
                        "${point.falseNotMet} (${pct(point.falseNotMetRate)}) |",
                )
            }
            appendLine()
        } else {
            appendLine("_No sweep: the arm's answers are self-reported or carry no distribution (J5)._")
            appendLine()
        }
        appendLine("### By kind")
        appendLine()
        renderBreakdown(byKind(armId))
        val families = byFamily(armId)
        if (families.isNotEmpty()) {
            appendLine("### By family")
            appendLine()
            renderBreakdown(families)
        }
        appendLine("### Stability, latency, usage")
        appendLine()
        val stability = stability(armId)
        appendLine(
            "- Repeat agreement: ${stability.agreeingCases} of ${stability.casesWithAllRepeats} cases " +
                "(${pct(stability.agreement)}) answered the same on every repeat.",
        )
        latency(armId)?.let {
            appendLine("- Latency: p50 ${it.p50Ms} ms, p95 ${it.p95Ms} ms over ${it.samples} calls.")
        }
        val usage = usage(armId)
        appendLine(
            "- Usage: ${usage.callsReportingTokens} of ${usage.calls} calls reported tokens" +
                (usage.inputTokens?.let { "; input $it" } ?: "") +
                (usage.outputTokens?.let { ", output $it" } ?: "") +
                (usage.reportedCostUsd?.let { "; reported cost USD ${it.fmt(4)}" } ?: "") + ".",
        )
        val missing = missing(armId)
        if (missing.isNotEmpty()) {
            appendLine("- Missing or malformed: ${missing.size} call(s). First: `${missing.first().error}`")
        }
        appendLine()
    }

    private fun StringBuilder.renderBreakdown(rows: Map<String, Confusion>) {
        appendLine("| Group | Calls | False met | False not met | Missing |")
        appendLine("| -- | -- | -- | -- | -- |")
        rows.forEach { (name, confusion) ->
            appendLine(
                "| `$name` | ${confusion.total} | ${confusion.falseMet} (${pct(confusion.falseMetRate)}) | " +
                    "${confusion.falseNotMet} (${pct(confusion.falseNotMetRate)}) | ${confusion.missing} |",
            )
        }
        appendLine()
    }

    private fun Aggregate.label(): String = when (this) {
        Aggregate.MeanOfRepeats -> "mean of repeats"
        Aggregate.SingleCall -> "single call"
    }

    companion object {
        /** The sweep has `SWEEP_STEPS + 1` points: 0.00, 0.05 … 1.00. */
        const val SWEEP_STEPS: Int = 20

        private const val EPSILON = 1e-9

        private fun List<Long>.nearestRank(percentile: Double): Long {
            val rank = ceil(percentile * size).toInt().coerceIn(1, size)
            return this[rank - 1]
        }

        /** A share as a percentage with one decimal, or `n/a`. */
        fun pct(share: Double?): String = share?.let { "${((it * 1000).roundToInt() / 10.0)}%" } ?: "n/a"

        private fun Double.fmt(decimals: Int = 2): String {
            var scale = 1.0
            repeat(decimals) { scale *= 10 }
            val rounded = (this * scale).roundToInt()
            val whole = rounded / scale.toInt()
            val fraction = (rounded % scale.toInt()).toString().padStart(decimals, '0')
            return "$whole.$fraction"
        }
    }
}
