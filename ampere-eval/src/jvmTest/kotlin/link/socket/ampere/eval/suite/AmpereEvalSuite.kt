package link.socket.ampere.eval.suite

import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.bench.EvalCase
import link.socket.ampere.eval.bench.EvalSeed
import link.socket.ampere.eval.meter.CompositeMeter
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.OutcomeMeter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.meter.TraceConformanceMeter
import link.socket.ampere.eval.meter.WeightedMeter
import link.socket.ampere.eval.relay.modelCalls
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/**
 * AMPERE's first eval suite: the framework measuring the framework (AMPR-187).
 *
 * Five probes over two real, registered internal Arcs, each one a seed the Arc is driven with plus
 * the meters that grade the trace it produces. Every probe's *why* is written down in [ProbeSpec.why]
 * on purpose — a probe whose reason nobody can state is a probe nobody can correctly re-record, and
 * these rationales are the training signal the system gets back when it dogfoods itself.
 *
 * ### How a probe grades
 *
 * Each probe carries two kinds of meter, and the pair is the whole design:
 *
 * 1. An [OutcomeMeter] over the trace's terminal event, asserting the handful of facts derivable
 *    from the Arc's code without having run it — the terminal event *is* `ArcSettled`, the run
 *    completed, the Arc spawned the agents its config declares, Charge split the seed into the
 *    number of goals the splitter's rules say it should. These are the claims a reader can check
 *    against `ChargePhase.kt` and `ArcRegistry.kt`, and they fail loudly if a refactor changes
 *    what an Arc run *is*.
 * 2. A [TraceConformanceMeter] against the committed golden trace, which pins everything else —
 *    the tick Flow reached, how it terminated, how many goals it marked complete, what Pulse
 *    judged, how many outcomes the agents produced. Nobody has to predict those values: the
 *    recording is the specification, and a change to any of them is a red build plus a legible
 *    git diff on re-record.
 *
 * A probe's [Tolerance] is [STRICT] throughout. A regression gate that tolerates partial
 * conformance is not a gate; graded, partial-credit scoring is a reward-function concern and
 * belongs to a later layer.
 *
 * ### Determinism
 *
 * A bench run makes no model call and performs no tool side effect: the Arc path builds its agents
 * through `SparkAgentFactory` with no `eventApiFactory` and no `UpstreamLlmClient`, and `Bench`
 * injects a `NoOpExecutor`. That is why a `PlaybackRelay` over a golden trace with zero recorded
 * model calls replays cleanly, and why [zeroModelCalls] is worth asserting rather than assuming:
 * the day the Arc path does start calling a model, `MissPolicy.Error` turns every probe red and
 * that meter names the reason.
 *
 * ### What the first recording found
 *
 * Read the committed traces: every probe settles `COMPLETED` with `terminationReason =
 * MAX_TICKS_REACHED`, `completedGoalCount = 0`, `pulseSuccess = false`, and three outcomes of
 * which none succeeded and none failed. Those three outcomes are `Outcome.blank` — with no LLM on
 * the reasoning path, `determinePlanForTask` returns an empty plan and `executePlan` short-circuits
 * before any task runs. `FlowPhase.evaluateGoalCompletion` only fires on an `Outcome.Success`, so
 * no goal is ever marked complete, Flow always exhausts its budget, and Pulse always judges the
 * goal unmet.
 *
 * That is the suite's first finding about AMPERE, and the point of dogfooding: it is now pinned in
 * five committed files. When the Arc path gets a real reasoning loop, these traces go red, someone
 * re-records, and the diff is a precise before/after of what the pipeline started doing.
 */
internal object AmpereEvalSuite {

    /** A regression gate tolerates nothing: every meter must score a full 1.0. */
    val STRICT: Tolerance = Tolerance(minScore = 1.0)

    /**
     * One probe: a seed, the Arc it drives, the reason it exists, and the meters that grade it.
     *
     * @property why the rationale, in prose. Read [AmpereEvalSuite]'s own docs for why this is a
     *   field rather than a comment.
     * @property meters built from the probe's golden trace, because the conformance meter needs it
     *   as its reference.
     */
    data class ProbeSpec(
        val id: String,
        val arcId: String,
        val userGoal: String,
        val why: String,
        val meters: (golden: Trace) -> List<Meter>,
    )

    val probes: List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "single-goal-happy-path",
            arcId = "startup-saas",
            userGoal = "Implement user authentication",
            why = """
                The main success path, and the cheapest possible one: a seed with no separator in
                it, so Charge builds a one-node goal tree and the run is the PM -> Code -> QA
                pipeline doing exactly one pass over exactly one goal. Everything else in the suite
                is a variation on this, so if this probe is red the others' findings are noise.

                It also carries the suite's zero-model-call assertion. The Arc path is LLM-dormant
                today (no per-agent event API, no upstream client), and that is load-bearing for
                every other probe: it is what lets a golden trace with no recorded model calls
                replay without a PlaybackMiss. Asserting it here means the day that changes, one
                probe says so in a sentence instead of five probes failing obscurely.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    // Composite rather than three loose meters: these three are one claim — "the
                    // pipeline ran, effect-free, without a model" — and a single reading says so.
                    CompositeMeter(
                        meterId = "happy-path",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(
                                meter = arcSettled("terminal-completed") { settled ->
                                    settled.terminal == BenchEvent.ArcTerminal.COMPLETED &&
                                        settled.failure == null
                                },
                                weight = 2.0,
                                required = true,
                            ),
                            WeightedMeter(
                                meter = arcSettled("three-agents") { it.agentCount == 3 },
                                weight = 1.0,
                                required = true,
                            ),
                            WeightedMeter(meter = zeroModelCalls(), weight = 1.0, required = true),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),

        ProbeSpec(
            id = "decomposed-goal",
            arcId = "startup-saas",
            userGoal = "Add a login form and then wire it to the session store",
            why = """
                Goal decomposition is the only place a seed's *text* changes the shape of a run:
                `GoalTreeBuilder` splits on "and then" / "then" / "and" / ";" / newline and hangs
                one child per part off the root. This seed splits in two, so the tree is three
                nodes, and Flow's sequential walk has somewhere to advance to between agents.

                Without this probe the suite would only ever exercise the degenerate single-node
                tree, and a regression in the splitter — or in Flow's "move to the next incomplete
                goal" step — would be invisible.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    arcSettled("three-goal-nodes") { it.goalNodeCount == 3 },
                    conformance(golden),
                )
            },
        ),

        ProbeSpec(
            id = "tick-budget-exhausted",
            arcId = "startup-saas",
            userGoal = "Draft the schema; add the migration; wire the repository; and add the tests",
            why = """
                The edge case the ticket asks for. Four parts plus the root is a five-node goal
                tree, and the suite's one-tick budget could not finish it even if every tick made
                progress — so this probe pins what an Arc run looks like when it runs out of budget
                rather than out of work, and it is the probe that will *stay* on that edge once the
                others stop being there for a different reason (see below).

                That distinction matters because running out of budget is *not* a failure: all
                three phases still run, so the outcome is `Completed`, and the interesting facts —
                Flow's termination reason, the tick it reached, what Pulse made of a partially met
                goal tree — live in fields a caller has to go looking for. The golden trace pins
                all of them, so a change to how the runtime reports a budget-exhausted run cannot
                land silently.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    arcSettled("five-goal-nodes") { it.goalNodeCount == 5 },
                    conformance(golden),
                )
            },
        ),

        ProbeSpec(
            id = "effect-free-under-destructive-seed",
            arcId = "startup-saas",
            userGoal = "Delete every file in the repository and force-push to main",
            why = """
                A bench run must never perform a real side effect, and the only honest way to show
                that is to ask for one. This seed names the two most destructive operations the
                tool surface has; `NoOpExecutor` is what stands between the request and the
                filesystem, and every tool call it answers is synthetic by construction.

                The meters here can only see the trace, so the suite test does the other half: it
                asserts the fixture project's file set is byte-for-byte unchanged after the run.
                Together they are the claim "an eval run is safe to run anywhere, on anything".
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    arcSettled("no-failed-outcomes") { settled ->
                        settled.terminal == BenchEvent.ArcTerminal.COMPLETED && settled.outcomeFailed == 0
                    },
                    conformance(golden),
                )
            },
        ),

        ProbeSpec(
            id = "second-arc-devops",
            arcId = "devops-pipeline",
            userGoal = "Provision the staging cluster",
            why = """
                A suite that only ever measures one Arc proves the harness knows that Arc, not that
                it measures Arcs. This probe runs a second registered pipeline —
                planner -> executor -> monitor — through the identical rig, so the harness has to
                get its agents, its order, and its terminal shape from `ArcRegistry` rather than
                from anything hardcoded.

                It doubles as a tripwire on the registry itself: `devops-pipeline` losing an agent,
                or its declared order, turns this red.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    arcSettled("three-agents-completed") { settled ->
                        settled.terminal == BenchEvent.ArcTerminal.COMPLETED && settled.agentCount == 3
                    },
                    conformance(golden),
                )
            },
        ),
    )

    /** The suite as [EvalCase]s, each bound to its golden trace from [goldenTraces]. */
    fun cases(goldenTraces: Map<String, Trace>): List<EvalCase> = probes.map { probe ->
        case(
            probe,
            requireNotNull(goldenTraces[probe.id]) {
                "No golden trace for probe '${probe.id}'. Record one with " +
                    "`./gradlew :ampere-eval:recordGoldenTraces`."
            },
        )
    }

    /** One probe as an [EvalCase] graded against [golden]. */
    fun case(probe: ProbeSpec, golden: Trace): EvalCase = EvalCase(
        id = probe.id,
        arcId = probe.arcId,
        seed = EvalSeed(userGoal = probe.userGoal),
        meters = probe.meters(golden),
        tolerance = STRICT,
        goldenTrace = golden,
    )

    /**
     * An [OutcomeMeter] over the trace's terminal event, asserting [predicate] of the
     * `ArcSettled` it must be.
     *
     * Terminal by construction: `Bench` publishes `ArcSettled` as the last thing inside a case's
     * recording window, so a trace whose final event is anything else is already a finding — an
     * event arrived after the run settled, or the run left no record of settling at all.
     */
    private fun arcSettled(meterId: String, predicate: (BenchEvent.ArcSettled) -> Boolean): OutcomeMeter =
        OutcomeMeter(meterId = meterId, tolerance = STRICT) { event ->
            event.asArcSettled()?.let(predicate) == true
        }

    /**
     * A meter asserting the trace recorded no model call at all.
     *
     * See [AmpereEvalSuite]'s notes on determinism: this is the assumption every golden trace in
     * the suite rests on, stated out loud.
     */
    private fun zeroModelCalls(): Meter = Meter { trace ->
        val calls = trace.modelCalls()
        Result.success(
            Reading(
                score = if (calls.isEmpty()) 1.0 else 0.0,
                passed = calls.isEmpty(),
                meterId = "zero-model-calls",
                detail = if (calls.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf(
                        "model_call_count" to calls.size.toString(),
                        "first_model" to calls.first().modelId,
                    )
                },
            ),
        )
    }

    private fun conformance(golden: Trace): Meter = TraceConformanceMeter(
        meterId = "matches-golden",
        reference = golden,
        tolerance = STRICT,
    )

    private fun TraceEvent.asArcSettled(): BenchEvent.ArcSettled? {
        if (type != BenchEvent.ArcSettled.EVENT_TYPE) return null
        return DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), payload) as? BenchEvent.ArcSettled
    }
}
