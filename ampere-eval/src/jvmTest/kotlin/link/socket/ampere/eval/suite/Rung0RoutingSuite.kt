package link.socket.ampere.eval.suite

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.eval.meter.CompositeMeter
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.OutcomeMeter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.meter.TraceConformanceMeter
import link.socket.ampere.eval.meter.WeightedMeter
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/**
 * The Rung 0 routing bench (AMPR-225): five routing decisions the relay makes about the 0-Watt
 * on-device floor, each pinned by a committed golden trace.
 *
 * ### Why this is not an Arc probe
 *
 * [AmpereEvalSuite] drives Arcs, and the Arc path makes no model call — so no Arc probe can ever
 * exercise a routing decision. Rung 0 *is* a routing decision: whether the relay sends a call to
 * the on-device model or routes around it. The seed here is therefore a [RoutingContext] rather
 * than a user goal, and the run is one `CognitiveRelayImpl.resolveWithMetadata` over the bundled
 * catalog and the default capability rules — the same relay and rules `SparkAgentFactory` builds
 * for a production agent.
 *
 * ### Why it is deterministic without a replay
 *
 * Routing is a local decision. The relay reads the catalog, the rules and the context, selects an
 * `AIConfiguration`, and publishes what it decided; no provider is called and no engine runs. The
 * same context over the same catalog produces the same events every time, so the gate runs the
 * *real* relay in CI and needs no `PlaybackRelay`, no API key and no network. What the on-device
 * engine reports about itself is the one input a real device would supply at runtime, and each
 * scenario states it as a [LocalCapacity] — the hardware check the AMPR-225 guardrail says the
 * selector owns.
 *
 * ### How a scenario grades
 *
 * The pairing is the one [AmpereEvalSuite] uses. An [OutcomeMeter] over the terminal event, plus
 * meters over the events before it, assert the facts a reader can derive from `CognitiveRelayImpl`
 * and `RoutingRule.ByCapability` without running them: which provider won, whether a fallback was
 * announced, and that the on-device route costs exactly zero Watts. A [TraceConformanceMeter]
 * against the golden trace pins everything else — which cloud model is the cheapest capable
 * runner-up, what it costs, how many candidates were compared. A catalog change that moves any of
 * those is a red build and a legible diff on re-record, which is what a bench is for.
 */
internal object Rung0RoutingSuite {

    /** A regression gate tolerates nothing: every meter must score a full 1.0. */
    val STRICT: Tolerance = Tolerance(minScore = 1.0)

    /** The `arcId` stamped on every routing scenario's trace. There is no Arc; this names the bench. */
    const val ARC_ID: String = "rung-0-routing"

    /** The agent every scenario routes as; stable, so it can sit in a golden payload. */
    const val AGENT_ID: String = "rung-0-bench"

    /** The reason a device reports when its hardware is not Apple Intelligence eligible. */
    const val INELIGIBLE_HARDWARE: String = "device_not_eligible"

    /** A provider id no descriptor in the bundled catalog carries. */
    const val FOREIGN_PROVIDER_ID: String = "some-other-local-engine"

    /** The largest prompt the bundled on-device model can take, from its descriptor. */
    private const val ON_DEVICE_CONTEXT_TOKENS: Int = 4096

    /**
     * One scenario: a routing context, the reason it exists, and the meters that grade the events
     * the relay publishes for it.
     *
     * @property why the rationale, in prose, for the same reason [AmpereEvalSuite.ProbeSpec.why]
     *   is a field: a scenario nobody can explain is a scenario nobody can correctly re-record.
     * @property meters built from the scenario's golden trace, because the conformance meter needs
     *   it as its reference.
     */
    data class ScenarioSpec(
        val id: String,
        val context: RoutingContext,
        val why: String,
        val meters: (golden: Trace) -> List<Meter>,
    )

    /** Capacity as an eligible device reports it: the engine is up and it is the on-device provider. */
    private val eligibleDevice: LocalCapacity = LocalCapacity(
        available = true,
        modelId = AIModel_OnDevice.AppleFoundationModels.name,
        maxContextTokens = ON_DEVICE_CONTEXT_TOKENS,
        providerId = AIProvider_OnDevice.id,
    )

    /** A plain text request with no floor: anything in the catalog could serve it. */
    private val textOnly: CapabilityRequirement = CapabilityRequirement(inputs = SupportedInputs.TEXT)

    val scenarios: List<ScenarioSpec> = listOf(
        ScenarioSpec(
            id = "rung-0-on-device-available",
            context = context(requirements = textOnly, localCapacity = eligibleDevice),
            why = """
                The floor doing its job. An eligible device reports its engine available, the
                request needs nothing the on-device model lacks, and cost-aware selection has a
                0-Watt candidate — so it must win, and the relay must say so with a RouteResolved
                that costs exactly zero. No RouteFallback precedes it: nothing was routed around.

                The golden trace also pins the runner-up — the cheapest metered model that could
                have served the call — and the saving against it. That is the honest Watt story
                the ticket asks for: the floor is free because the alternative was not.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    CompositeMeter(
                        meterId = "on-device-wins-at-zero-watts",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(
                                meter = terminalRouteResolved("resolved-on-device-at-0w") { resolved ->
                                    resolved.decision.providerName == AIProvider_OnDevice.name &&
                                        resolved.decision.modelName == AIModel_OnDevice.AppleFoundationModels.name &&
                                        resolved.estimatedWattCost == 0.0
                                },
                                weight = 2.0,
                                required = true,
                            ),
                            WeightedMeter(
                                meter = terminalRouteResolved("beat-a-metered-runner-up") { resolved ->
                                    resolved.candidateCount > 1 &&
                                        (resolved.runnerUpWattCost ?: 0.0) > 0.0 &&
                                        resolved.savingsVsRunnerUp == resolved.runnerUpWattCost
                                },
                                weight = 1.0,
                                required = true,
                            ),
                            WeightedMeter(meter = noRouteFallback(), weight = 1.0, required = true),
                            WeightedMeter(
                                meter = routeSelected("selected-on-device-not-as-fallback") { selected ->
                                    selected.decision.providerName == AIProvider_OnDevice.name &&
                                        !selected.decision.isFallback
                                },
                                weight = 1.0,
                                required = true,
                            ),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),

        ScenarioSpec(
            id = "rung-0-ineligible-hardware-falls-back",
            context = context(
                requirements = textOnly,
                localCapacity = LocalCapacity(
                    available = false,
                    providerId = AIProvider_OnDevice.id,
                    reason = INELIGIBLE_HARDWARE,
                ),
            ),
            why = """
                The honor-and-eat fallback, for the one reason the AMPR-225 guardrail names: the
                hardware is not eligible. The on-device model could serve the request — it is
                capable — but its availability gate is closed, so the relay must announce a
                RouteFallback naming the on-device provider and the device's own reason, then
                select the cheapest metered model and resolve it at a cost above zero.

                The fallback event is the point. A relay that silently picked a cloud model would
                be correct and unobservable; this scenario pins that the routing-around is said
                out loud, with the reason the device gave, and that the selection carries the
                fallback flag.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    CompositeMeter(
                        meterId = "announced-fallback-to-metered",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(
                                meter = routeFallback("fallback-names-on-device-and-reason") { fallback ->
                                    fallback.failedProvider == AIProvider_OnDevice.id &&
                                        fallback.failedModel == AIModel_OnDevice.AppleFoundationModels.name &&
                                        fallback.failureReason == INELIGIBLE_HARDWARE
                                },
                                weight = 2.0,
                                required = true,
                            ),
                            WeightedMeter(meter = resolvedMetered(), weight = 1.0, required = true),
                            WeightedMeter(
                                meter = routeSelected("selected-as-fallback") { it.decision.isFallback },
                                weight = 1.0,
                                required = true,
                            ),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),

        ScenarioSpec(
            id = "rung-0-capacity-from-another-provider",
            context = context(
                requirements = textOnly,
                localCapacity = LocalCapacity(available = true, providerId = FOREIGN_PROVIDER_ID),
            ),
            why = """
                A capacity snapshot that says "available" is not enough: the gate opens only when
                the snapshot's provider is the descriptor's. A host whose own local engine reports
                availability under a different provider id has not made the bundled on-device
                model available, and the relay must treat the gate as closed — a RouteFallback with
                the default reason, then a metered selection.

                This is the AMPR-327 finding about an engine that reports no provider id, pinned
                as behavior rather than remembered as a note. Loosening the gate to "any available
                engine" would turn this scenario red at the fallback meter.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    CompositeMeter(
                        meterId = "foreign-capacity-closes-the-gate",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(
                                meter = routeFallback("fallback-with-default-reason") { fallback ->
                                    fallback.failedProvider == AIProvider_OnDevice.id &&
                                        fallback.failureReason == RoutingRule.ByCapability.DEFAULT_UNAVAILABLE_REASON
                                },
                                weight = 2.0,
                                required = true,
                            ),
                            WeightedMeter(meter = resolvedMetered(), weight = 1.0, required = true),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),

        ScenarioSpec(
            id = "rung-0-below-requested-floor",
            context = context(
                requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT, minRung = CapabilityRung.THREE),
                localCapacity = eligibleDevice,
            ),
            why = """
                Insufficient for the requested quality tier. The device is eligible and the engine
                is up, but the call declares a floor of rung THREE and the on-device model is rung
                ZERO. Cheapest-capable must not reach below the floor, however free the floor is:
                the relay selects the cheapest metered model at or above THREE, at a cost above
                zero.

                No RouteFallback here, and that absence is asserted. A fallback means "capable but
                unavailable"; a model under the floor is not capable, so there is nothing to route
                around and nothing to announce. Confusing the two would misreport every floored
                call on every eligible device as a hardware problem.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    CompositeMeter(
                        meterId = "floor-excludes-rung-0",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(meter = resolvedMetered(), weight = 2.0, required = true),
                            WeightedMeter(meter = noRouteFallback(), weight = 1.0, required = true),
                            WeightedMeter(
                                meter = routeSelected("selected-cloud-not-as-fallback") { selected ->
                                    selected.decision.providerName != AIProvider_OnDevice.name &&
                                        !selected.decision.isFallback
                                },
                                weight = 1.0,
                                required = true,
                            ),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),

        ScenarioSpec(
            id = "rung-0-context-exceeds-window",
            context = context(
                requirements = CapabilityRequirement(
                    inputs = SupportedInputs.TEXT,
                    minContextTokens = ON_DEVICE_CONTEXT_TOKENS * 2,
                ),
                localCapacity = eligibleDevice,
            ),
            why = """
                The other way a request outgrows the floor: not quality but size. The on-device
                model's window is the smallest in the catalog, and a call that needs twice it is
                one the model cannot take. Like the floor scenario this is a capability miss, not
                an availability miss — no fallback is announced, and the cheapest metered model
                with a large enough window wins.

                It pins the descriptor's window as routing input. `OnDeviceInferenceSession`
                copies the probed window onto the descriptor so the engine's real limit governs
                routing; this scenario is what notices if that copy stops mattering.
            """.trimIndent(),
            meters = { golden ->
                listOf(
                    CompositeMeter(
                        meterId = "window-excludes-rung-0",
                        tolerance = STRICT,
                        children = listOf(
                            WeightedMeter(meter = resolvedMetered(), weight = 2.0, required = true),
                            WeightedMeter(meter = noRouteFallback(), weight = 1.0, required = true),
                        ),
                    ),
                    conformance(golden),
                )
            },
        ),
    )

    /** The suite as [RoutingCase]s, each bound to its golden trace from [goldenTraces]. */
    fun cases(goldenTraces: Map<String, Trace>): List<RoutingCase> = scenarios.map { scenario ->
        case(
            scenario,
            requireNotNull(goldenTraces[scenario.id]) {
                "No golden trace for routing scenario '${scenario.id}'. Record one with " +
                    "`./gradlew :ampere-eval:recordGoldenTraces`."
            },
        )
    }

    /** One scenario as a [RoutingCase] graded against [golden]. */
    fun case(scenario: ScenarioSpec, golden: Trace): RoutingCase = RoutingCase(
        id = scenario.id,
        context = scenario.context,
        meters = scenario.meters(golden),
        tolerance = STRICT,
    )

    /** Every scenario's golden trace, keyed by scenario id. */
    fun loadGoldenTraces(): Map<String, Trace> = GoldenTraces.loadAll(scenarios.map { it.id })

    private fun context(requirements: CapabilityRequirement, localCapacity: LocalCapacity): RoutingContext =
        RoutingContext(
            agentId = AGENT_ID,
            requirements = requirements,
            localCapacity = localCapacity,
        )

    // region — meters

    /**
     * An [OutcomeMeter] asserting [predicate] of the `RouteResolved` the terminal event must be.
     *
     * Terminal by construction: `CognitiveRelayImpl` emits the fallback, then the selection, then
     * the cost resolution, and every scenario here engages cost-aware selection. A trace that ends
     * in anything else is already a finding.
     */
    private fun terminalRouteResolved(
        meterId: String,
        predicate: (RoutingEvent.RouteResolved) -> Boolean,
    ): OutcomeMeter = OutcomeMeter(meterId = meterId, tolerance = STRICT) { event ->
        (event.decoded() as? RoutingEvent.RouteResolved)?.let(predicate) == true
    }

    /** The terminal resolution went to a metered model: not on-device, and not free. */
    private fun resolvedMetered(): Meter = terminalRouteResolved("resolved-metered-above-0w") { resolved ->
        resolved.decision.providerName != AIProvider_OnDevice.name && resolved.estimatedWattCost > 0.0
    }

    /** Asserts [predicate] of the trace's single `RouteSelected`. */
    private fun routeSelected(meterId: String, predicate: (RoutingEvent.RouteSelected) -> Boolean): Meter =
        eventsMeter(meterId) { events ->
            events.filterIsInstance<RoutingEvent.RouteSelected>().singleOrNull()?.let(predicate) == true
        }

    /** Asserts [predicate] of the trace's single `RouteFallback`. */
    private fun routeFallback(meterId: String, predicate: (RoutingEvent.RouteFallback) -> Boolean): Meter =
        eventsMeter(meterId) { events ->
            events.filterIsInstance<RoutingEvent.RouteFallback>().singleOrNull()?.let(predicate) == true
        }

    /** Asserts the relay announced no fallback at all: nothing capable was routed around. */
    private fun noRouteFallback(): Meter = eventsMeter("no-route-fallback") { events ->
        events.none { it is RoutingEvent.RouteFallback }
    }

    /** A meter over every decoded event in the trace; `1.0` when [predicate] holds, `0.0` otherwise. */
    private fun eventsMeter(meterId: String, predicate: (List<Event>) -> Boolean): Meter = Meter { trace ->
        val events = trace.events.mapNotNull { it.decoded() }
        val matched = predicate(events)
        Result.success(
            Reading(
                score = if (matched) 1.0 else 0.0,
                passed = matched,
                meterId = meterId,
                detail = if (matched) emptyMap() else mapOf("events" to events.joinToString { it.eventType }),
            ),
        )
    }

    private fun conformance(golden: Trace): Meter = TraceConformanceMeter(
        meterId = "matches-golden",
        reference = golden,
        tolerance = STRICT,
    )

    private fun TraceEvent.decoded(): Event? =
        runCatching { DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), payload) }.getOrNull()

    // endregion
}
