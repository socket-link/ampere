package link.socket.ampere.probe.safety

import kotlinx.serialization.Serializable
import link.socket.ampere.probe.Probe
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict

/**
 * Hazard guarantee: does this plan tell a person to do something dangerous
 * without a step that makes it safe first? (AMPR-380)
 *
 * Ampere owns this Probe because the question is generic to any plan of physical
 * work — Contract 2, guarantees descend to Probes — and because both halves of
 * the evidence are already Ampere-visible: the work graph is a `CanonWorkGraph`,
 * and the manifest reaches here through [LineRef], the way an observation reaches
 * `FreshnessProbe` through `Observed`. Rendering, disclaimer copy and App Review
 * notes stay with the consumer (Socket, SCKT-747), and routing on the verdict
 * stays consumer-side like every other Probe (Socket decision D21).
 *
 * Verdicts:
 *
 * - **A hazard is [Verdict.Warn], never [Verdict.Violated].** A plan with a
 *   hazard is not a wrong plan; it is a plan that needs a mitigation Task. Making
 *   it disqualifying would turn a safety line into a refusal to plan, which is
 *   explicitly out of scope.
 * - **A Task the classifier cannot read is [Verdict.Undetermined]**, and that
 *   outranks the warning: when some Tasks could not be judged, the verdict says
 *   so rather than reporting the hazards it did find as if the plan had been read
 *   in full (Socket decision D20 — `Undetermined` never renders as a soft pass).
 *   The findings it *did* reach are still on the [SafetyInspection], so the
 *   Inspector rule inserts their mitigations either way.
 * - **[Verdict.Holds] only when every Task was read and none carried a category.**
 *
 * A mitigated plan still warns. The Warn says the plan contains hazardous work,
 * not that the plan is unfinished — [MitigationPlan] is idempotent, so re-running
 * the Probe after insertion adds nothing and the Room stays quiet. The mitigation
 * Tasks themselves are not inspected ([MitigationPlan.isMitigation]): a mitigation
 * is the safety step, and its title quotes the hazardous step it guards, so
 * reading it would find the same hazard for ever.
 *
 * Cost: whatever [classifier] costs. [KeywordHazardClassifier] is 0W, which is
 * the only reason this can run on every plan edit.
 */
class SafetyProbe(
    private val classifier: HazardClassifier,
    override val id: ProbeId = ProbeId(ID),
) : Probe<WorkPlanSubject> {

    /**
     * The findings behind the verdict.
     *
     * [evaluate] returns only a `Verdict` because that is the SPI, and a verdict
     * event is primitives plus `Verdict` by design — a subject-typed payload
     * would be unusable to a consumer whose subject Ampere cannot name. The
     * Inspector rule needs the findings themselves, so it calls this and gets
     * them typed. Safe to recompute: the classifier is deterministic, and a
     * verdict is recomputed rather than replayed.
     */
    suspend fun inspect(subject: WorkPlanSubject): SafetyInspection {
        val findings = mutableListOf<HazardFinding>()
        val unclassified = mutableListOf<UnclassifiedSubject>()
        val itemIds = subject.graph.items.mapTo(mutableSetOf()) { it.canonId }
        // A mitigation Task quotes the step it guards, so a keyword classifier would
        // find the same hazard in it and ask for a mitigation of the mitigation.
        val inspectable = subject.graph.items.filterNot(MitigationPlan::isMitigation)

        inspectable.forEach { item ->
            val lines = subject.lines.filter { item.canonId in it.appliesTo }
            classifier.classify(item, lines)
                .onSuccess { findings += it }
                .onFailure { unclassified += unclassifiedOf(HazardSubject.Task(item.canonId), it) }
        }

        // A hazardous line no Task uses is still in the room with the person.
        val unattached = subject.lines.filter { line -> line.appliesTo.none { it in itemIds } }
        if (unattached.isNotEmpty()) {
            classifier.classifyUnattachedLines(unattached)
                .onSuccess { findings += it }
                .onFailure { unclassified += unclassifiedOf(HazardSubject.Line(unattached.first().lineId), it) }
        }

        return SafetyInspection(
            findings = findings.toList(),
            unclassified = unclassified.toList(),
            tasksInspected = inspectable.size,
        )
    }

    override suspend fun evaluate(subject: WorkPlanSubject): Verdict = inspect(subject).verdict

    private fun unclassifiedOf(fallback: HazardSubject, failure: Throwable): UnclassifiedSubject =
        when (failure) {
            is UnclassifiableSubject ->
                UnclassifiedSubject(failure.subject, failure.message, failure.undeterminedCause)
            else -> UnclassifiedSubject(
                subject = fallback,
                reason = failure.message ?: failure::class.simpleName ?: "classifier failed",
                cause = UndeterminedCause.EVIDENCE_UNREADABLE,
            )
        }

    companion object {
        /** The [ProbeId] this Probe registers under by default. */
        const val ID: String = "ampere.safety"
    }
}

/**
 * What [SafetyProbe.inspect] found: every hazard, every subject it could not
 * judge, and the [verdict] those two imply.
 *
 * `@Serializable` and primitives-only, so a consumer can persist an inspection
 * beside the plan edit that produced it without a conversion step.
 *
 * @property findings In plan order: the graph's items in their own order, each
 *   item's hazards in the classifier's rule order, then hazards on manifest lines
 *   no Task uses. Deterministic, so two runs over one plan are comparable.
 * @property unclassified Subjects the classifier could not judge. Non-empty means
 *   the verdict is [Verdict.Undetermined] whatever else was found.
 * @property tasksInspected How many Tasks were read, so a `Holds` can say what it
 *   is a statement about.
 */
@Serializable
data class SafetyInspection(
    val findings: List<HazardFinding> = emptyList(),
    val unclassified: List<UnclassifiedSubject> = emptyList(),
    val tasksInspected: Int = 0,
) {

    /** Hazard categories present anywhere in the plan, in first-seen order. */
    val categories: Set<HazardCategory> get() = findings.mapTo(linkedSetOf()) { it.category }

    val verdict: Verdict
        get() = when {
            unclassified.isNotEmpty() -> Verdict.Undetermined(
                reason = buildString {
                    append("could not classify ${unclassified.size} subject(s): ")
                    append(summarize(unclassified.map { "${it.subject.value} (${it.reason})" }))
                    if (findings.isNotEmpty()) append("; ${findings.size} hazard(s) found in the rest")
                },
                cause = if (unclassified.any { it.cause == UndeterminedCause.EVIDENCE_UNREADABLE }) {
                    UndeterminedCause.EVIDENCE_UNREADABLE
                } else {
                    unclassified.first().cause
                },
            )

            findings.isNotEmpty() -> Verdict.Warn(
                reason = "${findings.size} hazard(s): " +
                    summarize(findings.map { "${it.category.name} on ${it.subject.value}" }),
            )

            else -> Verdict.Holds(reason = "no hazard category in $tasksInspected task(s)")
        }

    /**
     * At most [MAX_SUMMARIZED] entries, then a count. A verdict's reason lands in
     * every trace that captures the event, so it is bounded by construction rather
     * than by how big the plan happens to be.
     */
    private fun summarize(entries: List<String>): String =
        entries.take(MAX_SUMMARIZED).joinToString(", ") +
            if (entries.size > MAX_SUMMARIZED) ", and ${entries.size - MAX_SUMMARIZED} more" else ""

    companion object {
        const val MAX_SUMMARIZED: Int = 4
    }
}
