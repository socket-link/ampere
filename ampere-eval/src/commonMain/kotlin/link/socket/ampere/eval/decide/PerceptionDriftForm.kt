package link.socket.ampere.eval.decide

import kotlin.math.roundToInt
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.propel.RunQuestions

/**
 * The two question forms the perception-drift bench asks (AMPR-482 task 2).
 *
 * The shipped form is [RunQuestions.perceptionDrift], a `noul`, exactly as a hosted run asks
 * it after an executed cycle under `PerceiveCadence.Record` (AMPR-481). The second is the same
 * wording as a three-option `choice` — `bears`, `beside`, `cannot_tell` — because AMPR-422
 * found the form matters. **`cannot_tell` is counted on the bears side**: the costly error
 * here is calling a change that bore on the goal "beside" it, which leaves the run perceiving a
 * world that has moved, so a model that cannot say is treated as if the world may have.
 *
 * The choice's texts are *derived* from the shipped noul rather than written here, so a change
 * to `RunQuestions` moves both forms together.
 *
 * ### Which side is "positive"
 *
 * [DecisionBenchReport] counts its two errors as *false met* (the costly one, on which the
 * zero-false operating point is fitted) and *false not met*. For this bench the costly error is
 * **false settled** — a bears case answered beside — so the keys handed to the bench as
 * `positiveKeys` are the **settled** keys ([settledKeys]): the report's "met" reads as
 * *settled*, its "false met" as *false settled*, and its zero-false-met operating point as the
 * zero-false-settled one. [PerceptionDriftReport] renders it in those words.
 */
enum class PerceptionDriftForm(val question: Question, val bearsKey: String, val settledKey: String) {

    /** The shipped `RunQuestions.perceptionDrift`: `true` bears on the goal, `false` is beside it. */
    Noul(
        question = RunQuestions.perceptionDrift,
        bearsKey = Question.Noul.TRUE_KEY,
        settledKey = Question.Noul.FALSE_KEY,
    ),

    /** The same question as a three-option choice; `cannot_tell` reads as bears. */
    Choice(
        question = Question.Choice(
            instructions = RunQuestions.perceptionDrift.instructions,
            criteria = linkedMapOf(
                BEARS_KEY to RunQuestions.perceptionDrift.criteria.getValue(Question.Noul.TRUE_KEY),
                BESIDE_KEY to RunQuestions.perceptionDrift.criteria.getValue(Question.Noul.FALSE_KEY),
                CANNOT_TELL_KEY to "The changes do not show enough to say whether any of them bears on the goal",
            ),
        ),
        bearsKey = BEARS_KEY,
        settledKey = BESIDE_KEY,
    ),
    ;

    /**
     * The answer keys the bench reads as "settled" — what it is handed as `positiveKeys`. Only
     * the one settled key: in the choice form `cannot_tell` is on the bears side.
     */
    val settledKeys: Set<String>
        get() = setOf(settledKey)

    /** Every key read as "bears": the bears key, and `cannot_tell` in the choice form. */
    val bearsKeys: Set<String>
        get() = question.answerKeys.toSet() - settledKeys

    /** The wire name of the form, for an arm id or a file name. */
    val wireName: String
        get() = name.lowercase()

    /**
     * [case], asked in this form: its question swapped and its label translated. A case is
     * authored in [Noul] form; this is how the choice form is derived from the same corpus.
     */
    fun of(case: DecisionCase): DecisionCase = case.copy(
        question = question,
        expected = if (case.expected in Noul.bearsKeys || case.expected == BEARS) bearsKey else settledKey,
    )

    companion object {
        const val BEARS: String = BEARS_KEY
        const val BESIDE: String = BESIDE_KEY
        const val CANNOT_TELL: String = CANNOT_TELL_KEY
    }
}

// File-level, because an enum entry's initializer runs before the enum's companion exists.
private const val BEARS_KEY = "bears"
private const val BESIDE_KEY = "beside"
private const val CANNOT_TELL_KEY = "cannot_tell"

/**
 * A [DecisionBenchReport] of the perception-drift bench, read in its own words (AMPR-482
 * task 4).
 *
 * The bench is run with a form's [PerceptionDriftForm.settledKeys] as `positiveKeys`, so in the
 * underlying report "met" is *settled*: `falseMet` is a bears case called settled (**false
 * settled**, the costly error), `falseNotMet` is a beside case called bears (**false moved**,
 * a wasted re-perceive), and the zero-false-met operating point is the zero-false-settled one.
 * This object does the translation once, so the document and the tests quote one vocabulary.
 *
 * ### The threshold, on P(bears)
 *
 * The report's threshold is on `P(settled)`, the mass on the settled key. The consumer's
 * number is on the other side — the `moveAbove` a `WhenDrifted` cadence would carry — and
 * for both forms `P(bears) = 1 − P(settled)`, because `cannot_tell` is on the bears side. So
 * the **zero-false-settled operating point** is: the lowest `P(bears)` any bears sample
 * reached ([OperatingPoint.moveAbove]); every bears case sits at or above it, and the rule
 * *bears when P(bears) ≥ moveAbove* reads none of them as settled. Beside it is the share of
 * beside samples strictly below it — what the arm still recognises as settled once it is
 * forbidden to be wrong the costly way.
 */
object PerceptionDriftReport {

    /**
     * The zero-false-settled operating point.
     *
     * @property moveAbove The lowest `P(bears)` a bears sample reached. Bears is `P(bears) ≥ moveAbove`.
     * @property settledRecognised Share of beside samples with `P(bears)` below it.
     * @property besideSamples How many beside samples that share is over.
     * @property bearsSamples How many bears samples set the threshold.
     */
    data class OperatingPoint(
        val moveAbove: Double,
        val settledRecognised: Double,
        val besideSamples: Int,
        val bearsSamples: Int,
    )

    /** One row of the sweep on `P(bears)`: the two error counts when bears means `P(bears) > moveAbove`. */
    data class SweepPoint(
        val moveAbove: Double,
        val falseSettled: Int,
        val falseMoved: Int,
        val bearsSamples: Int,
        val besideSamples: Int,
    ) {
        val falseSettledRate: Double? get() = bearsSamples.takeIf { it > 0 }?.let { falseSettled.toDouble() / it }
        val falseMovedRate: Double? get() = besideSamples.takeIf { it > 0 }?.let { falseMoved.toDouble() / it }
    }

    /** The confusion counts of [armId], in this bench's words. */
    data class Confusion(
        val falseSettled: Int,
        val falseMoved: Int,
        val bearsCalledBears: Int,
        val settledCalledSettled: Int,
        val missing: Int,
    ) {
        val bearsCases: Int get() = falseSettled + bearsCalledBears
        val besideCases: Int get() = falseMoved + settledCalledSettled
        val total: Int get() = bearsCases + besideCases
        val falseSettledRate: Double? get() = bearsCases.takeIf { it > 0 }?.let { falseSettled.toDouble() / it }
        val falseMovedRate: Double? get() = besideCases.takeIf { it > 0 }?.let { falseMoved.toDouble() / it }
    }

    /** [DecisionBenchReport.confusion] in this bench's words. */
    fun confusion(
        report: DecisionBenchReport,
        armId: String,
        include: (DecisionCase) -> Boolean = { true },
    ): Confusion = report.confusion(armId, include).asDrift()

    /** [DecisionBenchReport.byKind] in this bench's words. */
    fun byKind(report: DecisionBenchReport, armId: String): Map<String, Confusion> =
        report.byKind(armId).mapValues { (_, confusion) -> confusion.asDrift() }

    /** [DecisionBenchReport.byFamily] in this bench's words. */
    fun byFamily(report: DecisionBenchReport, armId: String): Map<String, Confusion> =
        report.byFamily(armId).mapValues { (_, confusion) -> confusion.asDrift() }

    /**
     * The zero-false-settled operating point of [armId], or null when the arm is not fittable
     * (`BandFitGuard` governs every sweep, AMPR-381 J5) or has no bears sample to set it.
     */
    fun zeroFalseSettled(
        report: DecisionBenchReport,
        armId: String,
        aggregate: DecisionBenchReport.Aggregate,
    ): OperatingPoint? = report.zeroFalseMet(armId, aggregate)?.let { point ->
        OperatingPoint(
            moveAbove = 1.0 - point.threshold,
            settledRecognised = point.metRecognised,
            besideSamples = point.metSamples,
            bearsSamples = point.unmetSamples,
        )
    }

    /**
     * The 21-point sweep of [armId] on `P(bears)`, ascending, or null when the arm is not
     * fittable. The report's point at `P(settled) ≥ t` is this sweep's point at
     * `P(bears) > 1 − t`.
     */
    fun sweep(
        report: DecisionBenchReport,
        armId: String,
        aggregate: DecisionBenchReport.Aggregate = DecisionBenchReport.Aggregate.SingleCall,
    ): List<SweepPoint>? = report.sweep(armId, aggregate)?.map { point ->
        SweepPoint(
            moveAbove = 1.0 - point.threshold,
            falseSettled = point.falseMet,
            falseMoved = point.falseNotMet,
            bearsSamples = point.unmetSamples,
            besideSamples = point.metSamples,
        )
    }?.sortedBy { it.moveAbove }

    /**
     * The report as Markdown in this bench's words: one section per arm with its confusion
     * counts, the zero-false-settled operating points, the sweep on `P(bears)`, per-kind and
     * per-family tables, stability, latency and usage. What the document quotes.
     */
    fun render(report: DecisionBenchReport, title: String = "Perception-drift bench report"): String = buildString {
        appendLine("# $title")
        appendLine()
        val bears = report.cases.count { !report.isMet(it) }
        appendLine("Cases: ${report.cases.size} ($bears bears, ${report.cases.size - bears} beside). ")
        appendLine("Repeats: ${report.repeats}. Settled keys: ${report.positiveKeys.joinToString()}.")
        appendLine()
        for (armId in report.armIds) {
            renderArm(report, armId)
        }
    }

    private fun StringBuilder.renderArm(report: DecisionBenchReport, armId: String) {
        appendLine("## Arm `$armId`")
        appendLine()
        if (report.recordsOf(armId).isEmpty()) {
            appendLine("_No records._")
            appendLine()
            return
        }
        val all = confusion(report, armId)
        appendLine("| Calls | False settled | False moved | Bears called bears | Beside called settled | Missing |")
        appendLine("| -- | -- | -- | -- | -- | -- |")
        appendLine(
            "| ${all.total} | ${all.falseSettled} (${pct(all.falseSettledRate)}) | " +
                "${all.falseMoved} (${pct(all.falseMovedRate)}) | ${all.bearsCalledBears} | " +
                "${all.settledCalledSettled} | ${all.missing} |",
        )
        appendLine()
        report.weakestKind(armId)?.let { (kind, errorRate) ->
            appendLine("Weakest kind: `$kind` (${pct(errorRate)} of its calls wrong).")
            appendLine()
        }
        if (report.isFittable(armId)) {
            appendLine("### Zero-false-settled operating point")
            appendLine()
            appendLine(
                "| Samples | moveAbove (bears when P(bears) ≥ it) | Settled recognised | " +
                    "Beside samples | Bears samples |",
            )
            appendLine("| -- | -- | -- | -- | -- |")
            for (aggregate in DecisionBenchReport.Aggregate.entries) {
                val point = zeroFalseSettled(report, armId, aggregate) ?: continue
                appendLine(
                    "| ${aggregate.label()} | ${point.moveAbove.fmt()} | ${pct(point.settledRecognised)} | " +
                        "${point.besideSamples} | ${point.bearsSamples} |",
                )
            }
            appendLine()
            appendLine("### Threshold sweep (single calls; bears when P(bears) > moveAbove)")
            appendLine()
            appendLine("| moveAbove | False settled | False moved |")
            appendLine("| -- | -- | -- |")
            sweep(report, armId)?.forEach { point ->
                appendLine(
                    "| ${point.moveAbove.fmt()} | ${point.falseSettled} (${pct(point.falseSettledRate)}) | " +
                        "${point.falseMoved} (${pct(point.falseMovedRate)}) |",
                )
            }
            appendLine()
        } else {
            appendLine("_No sweep: the arm's answers are self-reported or carry no distribution (J5)._")
            appendLine()
        }
        appendLine("### By kind")
        appendLine()
        renderBreakdown(byKind(report, armId))
        val families = byFamily(report, armId)
        if (families.isNotEmpty()) {
            appendLine("### By family")
            appendLine()
            renderBreakdown(families)
        }
        appendLine("### Stability, latency, usage")
        appendLine()
        val stability = report.stability(armId)
        appendLine(
            "- Repeat agreement: ${stability.agreeingCases} of ${stability.casesWithAllRepeats} cases " +
                "(${pct(stability.agreement)}) answered the same on every repeat.",
        )
        report.latency(armId)?.let {
            appendLine("- Latency: p50 ${it.p50Ms} ms, p95 ${it.p95Ms} ms over ${it.samples} calls.")
        }
        val usage = report.usage(armId)
        appendLine(
            "- Usage: ${usage.callsReportingTokens} of ${usage.calls} calls reported tokens" +
                (usage.inputTokens?.let { "; input $it" } ?: "") +
                (usage.outputTokens?.let { ", output $it" } ?: "") +
                (usage.reportedCostUsd?.let { "; reported cost USD ${it.fmt(4)}" } ?: "") + ".",
        )
        val missing = report.missing(armId)
        if (missing.isNotEmpty()) {
            appendLine("- Missing or malformed: ${missing.size} call(s). First: `${missing.first().error}`")
        }
        appendLine()
    }

    private fun StringBuilder.renderBreakdown(rows: Map<String, Confusion>) {
        appendLine("| Group | Calls | False settled | False moved | Missing |")
        appendLine("| -- | -- | -- | -- | -- |")
        rows.forEach { (name, confusion) ->
            appendLine(
                "| `$name` | ${confusion.total} | ${confusion.falseSettled} (${pct(confusion.falseSettledRate)}) | " +
                    "${confusion.falseMoved} (${pct(confusion.falseMovedRate)}) | ${confusion.missing} |",
            )
        }
        appendLine()
    }

    private fun DecisionBenchReport.Confusion.asDrift(): Confusion = Confusion(
        falseSettled = falseMet,
        falseMoved = falseNotMet,
        bearsCalledBears = unmetCalledUnmet,
        settledCalledSettled = metCalledMet,
        missing = missing,
    )

    private fun DecisionBenchReport.Aggregate.label(): String = when (this) {
        DecisionBenchReport.Aggregate.MeanOfRepeats -> "mean of repeats"
        DecisionBenchReport.Aggregate.SingleCall -> "single call"
    }

    private fun pct(share: Double?): String = DecisionBenchReport.pct(share)

    private fun Double.fmt(decimals: Int = 2): String {
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        val rounded = (this * scale).roundToInt()
        val whole = rounded / scale.toInt()
        val fraction = (rounded % scale.toInt()).toString().padStart(decimals, '0')
        return "$whole.$fraction"
    }
}
