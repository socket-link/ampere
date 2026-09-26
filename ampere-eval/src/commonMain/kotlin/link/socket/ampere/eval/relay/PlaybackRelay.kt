package link.socket.ampere.eval.relay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.UndecodedTraceEvent
import link.socket.ampere.trace.ReplayWindow
import link.socket.ampere.trace.WattCost
import link.socket.ampere.version.AMPERE_VERSION
import link.socket.ampere.version.SemanticVersion

/**
 * What a [PlaybackRelay] does when an Arc makes a model call for which the
 * [Trace] holds no recording (the Arc has *diverged* from the recording).
 *
 * Resolves the AMPR-184 OPEN DECISION (`miss-as-failure` vs. `miss-as-low-Reading`)
 * in favour of **miss-as-failure**: a strict miss is a hard, typed [PlaybackMiss]
 * that turns divergence into a red build. Grading divergence as a low score is a
 * reward-function concern (RFT/GRPO) that belongs to a later layer, not to the
 * relay — keeping this seam a clean boolean (replayed exactly, or diverged).
 */
sealed interface MissPolicy {
    /** Strict, eval mode: a miss yields `Result.failure(`[PlaybackMiss]`)`. */
    data object Error : MissPolicy

    /** Rewind handoff: a miss is routed to the live delegate (failure if none). */
    data object Delegate : MissPolicy
}

/**
 * Typed failure signalling that an Arc diverged from its recording: the
 * [callIndex]-th model call within [window] had no recorded counterpart under
 * [MissPolicy.Error].
 *
 * Carried as a `Result.failure` value by [PlaybackRelay.replay] (the Result
 * boundary) and thrown by the `CognitiveRelay` methods so the divergence
 * propagates up the call path as a red build.
 */
class PlaybackMiss(
    val callIndex: Int,
    val recordedCallCount: Int,
    /** The window whose call-index sequence was exhausted; `null` when not supplied. */
    val window: ReplayWindow? = null,
) : Exception(
    "PlaybackRelay diverged: model call #$callIndex has no recorded response " +
        "(trace recorded $recordedCallCount model call(s)).",
)

/**
 * Typed failure signalling that the trace was recorded by a **newer build** than the one
 * replaying it, and that this build could not read part of it (AMPR-363).
 *
 * This is the version-skew contract from `docs/concepts/domain-canon.md` applied at the trace
 * envelope, in the shape `BundleParseError.UnknownVersion` already established: an old consumer
 * is told "upgrade required", not "parse failed". Both halves are required before the claim is
 * made — undecodable events *and* a newer [producerVersion] — because either alone means
 * something else. Undecodable events under an older or equal producer are corruption or a
 * removed type, not skew; a newer producer whose events all decoded is simply forward
 * compatibility working, and replay proceeds.
 *
 * Reported by [PlaybackRelay.validate] as a `Result.failure` and **never thrown from a
 * constructor**: a trace's content must not be able to take down the object that reads it.
 *
 * @property producerVersion the `AMPERE_VERSION` of the build that recorded the trace.
 * @property currentVersion the `AMPERE_VERSION` of the build replaying it.
 * @property undecodedCount how many of the trace's events this build could not decode.
 */
class TraceVersionSkew(
    val producerVersion: String,
    val currentVersion: String,
    val undecodedCount: Int,
) : Exception(
    "Trace was recorded by Ampere $producerVersion but is being replayed by $currentVersion, " +
        "which could not decode $undecodedCount event(s). Upgrade to $producerVersion or newer " +
        "to replay this trace in full.",
)

/**
 * A relay that replays a [Trace]'s recorded model routing in order, behind the
 * exact [CognitiveRelay] interface (AMPR-184; interface per RECON-relay §1).
 *
 * The same class expresses both eval and rewind-and-correct:
 * - **Eval** (defaults): no delegate, [MissPolicy.Error], `branchIndex` past the
 *   end — every model call replays its recording, and a missing recording is a
 *   [PlaybackMiss] (divergence is itself a finding).
 * - **Rewind** : a [liveDelegate] plus a [branchIndex] of `k` — the first `k`
 *   calls replay, then call `k` onward is handed to the live delegate.
 *
 * ### What is (and isn't) replayed
 * `CognitiveRelay` is a **routing-only** seam: it selects an [AIConfiguration];
 * it never sees the prompt or the completion (RECON-relay Finding A), and the
 * recorded events carry no content (Finding B). This relay therefore replays the
 * recorded **routing decision in call order** with `reason = "playback"` and a
 * zero-Watt guarantee; it does **not** substitute response *content*. Because the
 * recorded provider/model are plain ids and reconstructing a live [AIConfiguration]
 * from them needs a provider registry the eval module deliberately does not depend
 * on, a replay hit returns the supplied `fallbackConfiguration` (the recorded
 * selection is available for inspection via [recordedCallAt]). Content-faithful
 * replay belongs to the `UpstreamLlmClient` seam in a later ticket.
 *
 * ### Window
 * Call indices count within the trace's [ReplayWindow] ([window]). A call-index
 * sequence only means something when it has an end — "past the recordings" is
 * what makes a [PlaybackMiss] detectable — so replay needs a bounded window. v1's
 * bound is the Arc run; see [ReplayWindow] for why that is a projection choice
 * rather than something baked into the `runId`.
 *
 * ### Watts
 * A replayed call performs **no live provider invocation**, so it consumes no
 * tokens and therefore zero Watts (RECON-relay §2.4). See [replayedWattCost].
 *
 * ### Version skew
 * Constructing a relay never fails on trace content (AMPR-363). An event this build cannot
 * decode is skipped and counted in [undecodedEvents] rather than thrown; whether that
 * degradation is *acceptable* is the caller's call, asked via [validate].
 *
 * @param trace the recorded window to replay (in v1, one Arc run).
 * @param missPolicy what to do when the Arc makes more (or different) calls than
 *   were recorded. Defaults to strict [MissPolicy.Error].
 * @param liveDelegate the relay used for branched/delegated calls. Required for
 *   [MissPolicy.Delegate] and for any call at/after [branchIndex]; a `null`
 *   delegate in those cases yields a `Result.failure`.
 * @param branchIndex the **call** index at which replay stops and the delegate
 *   takes over: calls `0 until branchIndex` replay, `branchIndex` onward delegate.
 *   Defaults to `trace.size` — an event count that is always ≥ the model-call
 *   count, i.e. "never branch" (the degenerate eval case, mirroring
 *   `TraceCursor.branchAfter(size - 1)`).
 * @param json the codec used to read the trace's payloads.
 * @param currentVersion the Ampere version doing the replaying, compared against
 *   [Trace.producerVersion] by [validate]. Defaults to this build's `AMPERE_VERSION`;
 *   overridable so a test can pin both sides of the comparison.
 */
class PlaybackRelay(
    private val trace: Trace,
    private val missPolicy: MissPolicy = MissPolicy.Error,
    private val liveDelegate: CognitiveRelay? = null,
    private val branchIndex: Int = trace.size,
    private val json: Json = DEFAULT_JSON,
    private val currentVersion: String = AMPERE_VERSION,
) : CognitiveRelay {

    private val decoded: DecodedModelCalls = trace.decodeModelCalls(json)
    private val recordedCalls: List<RecordedModelCall> get() = decoded.calls
    private val mutex = Mutex()
    private var nextCallIndex: Int = 0

    /** The window this relay replays; call indices are positions within it. */
    val window: ReplayWindow get() = trace.window

    /** The ordered recorded model calls this relay replays. */
    val recordedCallCount: Int get() = recordedCalls.size

    /**
     * Events in the trace this build could not decode, in trace order (AMPR-363).
     *
     * Empty for a trace this build understands completely. A non-empty list does not by itself
     * stop replay — the skipped events may be irrelevant to it — but it is the counted fact a
     * bench asserts on, and the reason half of [validate]'s verdict.
     */
    val undecodedEvents: List<UndecodedTraceEvent> get() = decoded.undecodedEvents

    /**
     * The Watt cost charged for a replayed call: **zero**. Replayed calls make no
     * live provider invocation, so they consume no tokens (RECON-relay §2.4).
     * Exposed so callers/tests can assert the zero-Watt contract explicitly.
     */
    val replayedWattCost: WattCost = WattCost()

    override val config: RelayConfig = RelayConfig()

    override suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration =
        when (val resolution = resolveWithMetadata(context, fallbackConfiguration)) {
            is RoutingResolution.Success -> resolution.configuration
            is RoutingResolution.FloorUnmet -> throw RoutingFloorUnmetException(
                requestedFloor = resolution.requestedFloor,
                bestAvailableRung = resolution.bestAvailableRung,
            )
        }

    override suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution = replay(context, fallbackConfiguration).getOrThrow()

    /**
     * The Result-boundary core of the relay (RECON-relay: "Result boundaries").
     * For the next call index `i`:
     * - `i >= branchIndex` → **branch**: route to [liveDelegate] (failure if none).
     * - `i < recordedCallCount` → **replay hit**: the recorded selection, zero Watts.
     * - otherwise (`i` within the replay window but past the recordings) → **miss**:
     *   [MissPolicy.Error] → `failure(`[PlaybackMiss]`)`; [MissPolicy.Delegate] →
     *   route to [liveDelegate] (failure if none).
     *
     * The branch check precedes the replay check so a `branchIndex` inside the
     * recorded range still hands off live (rewind-and-correct, AMPR-184 task 2.4).
     */
    suspend fun replay(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): Result<RoutingResolution> {
        val index = mutex.withLock { nextCallIndex++ }
        return when {
            index >= branchIndex -> delegate(context, fallbackConfiguration, index)
            index < recordedCalls.size -> Result.success(
                RoutingResolution.Success(configuration = fallbackConfiguration, reason = PLAYBACK_REASON),
            )
            missPolicy == MissPolicy.Delegate -> delegate(context, fallbackConfiguration, index)
            else -> Result.failure(PlaybackMiss(index, recordedCalls.size, window))
        }
    }

    /**
     * Whether this build can replay the trace faithfully.
     *
     * `Result.failure(`[TraceVersionSkew]`)` when the trace holds events this build could not
     * decode **and** [Trace.producerVersion] is strictly newer than [currentVersion] — the
     * version-skew case, where the honest report is "upgrade required" rather than a shorter
     * replay that looks complete. Success otherwise, including for:
     * - a trace with no [Trace.producerVersion] at all (everything recorded before AMPR-363),
     *   which behaves exactly as it did before the stamp existed;
     * - a trace from an older or equal build, whose undecodable events are corruption or a
     *   removed type rather than skew;
     * - a newer trace this build read in full, which is forward compatibility working.
     *
     * This is a question, not a gate: nothing calls it on the replay path, and replay works the
     * same whether or not a caller asks. A bench that wants skew to be a red build checks it.
     */
    fun validate(): Result<Unit> {
        val undecoded = undecodedEvents
        if (undecoded.isEmpty()) return Result.success(Unit)

        val producerVersion = trace.producerVersion ?: return Result.success(Unit)
        if (!SemanticVersion.isNewer(candidate = producerVersion, reference = currentVersion)) {
            return Result.success(Unit)
        }

        return Result.failure(
            TraceVersionSkew(
                producerVersion = producerVersion,
                currentVersion = currentVersion,
                undecodedCount = undecoded.size,
            ),
        )
    }

    /** Recorded model call at [index] in replay order, or `null` if out of range. */
    fun recordedCallAt(index: Int): RecordedModelCall? = recordedCalls.getOrNull(index)

    override suspend fun updateConfig(newConfig: RelayConfig) {
        // No-op: playback selection is driven by the recorded Trace, not routing rules.
    }

    private suspend fun delegate(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
        index: Int,
    ): Result<RoutingResolution> {
        val delegate = liveDelegate ?: return Result.failure(
            IllegalStateException(
                "PlaybackRelay call #$index must be served live, but no liveDelegate was provided.",
            ),
        )
        return Result.success(delegate.resolveWithMetadata(context, fallbackConfiguration))
    }

    companion object {
        /** The `RoutingResolution.reason` stamped on every replayed selection. */
        const val PLAYBACK_REASON: String = "playback"
    }
}
