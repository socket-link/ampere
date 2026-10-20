package link.socket.ampere.cli.ondevice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.routing.local.InferenceLocalityClassifier
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.llm.LocalFirstOutcome
import link.socket.ampere.llm.OnDeviceInferenceSession

/**
 * The CLI's on-device path, or why it has none (AMPR-375).
 *
 * Rung ZERO serves display text only: words a person reads that nothing in a run acts on, decides
 * on or remembers (AMPR-372). No agent call can reach the device, whatever engine is bound
 * (AMPR-459), so the engine is not bound into the agents' transports. Binding it there would route
 * nothing and add a helper probe and a token count to every agent call. It is bound to exactly one
 * thing: an [OnDeviceInferenceSession] for display text, which the TUI reaches through `:ask`.
 *
 * The session is on-device only, with no cloud transport, so nothing it is asked can leave the
 * machine. It publishes its `ProviderCall*` events through the CLI's own door, under its own agent
 * id, so they are stored with everything else, the event stream shows them, and the session's
 * monitor folds every call on the CLI's bus into [Bound.state].
 */
sealed interface OnDeviceAssistant : AutoCloseable {

    /**
     * A helper was found. [ask] goes to the device, and [state] says what the device is doing.
     *
     * @param engine The engine the session runs on: the helper's in production, a fake in tests.
     * @param eventApi The door the session's events are published through.
     * @param resources Closed with the session: the helper process in production.
     */
    class Bound(
        engine: LocalInferenceEngine,
        eventApi: AgentEventApi,
        private val resources: AutoCloseable? = null,
    ) : OnDeviceAssistant {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val session = OnDeviceInferenceSession(engine = engine, eventApi = eventApi, scope = scope)

        /** What the on-device indicator shows. */
        val state: StateFlow<OnDeviceInferenceState>
            get() = session.state

        /** What decides which calls on the bus ran on this device: the session's own client. */
        val classifier: InferenceLocalityClassifier
            get() = session.classifier

        /** Probe the device now. Spawns the helper the first time. */
        suspend fun refreshAvailability(): LocalCapacity = session.refreshAvailability()

        /**
         * Answer [question] on the device, as display text. The answer goes back to the caller and
         * nowhere else: not into a run, a plan, a judgment or memory.
         */
        suspend fun ask(question: String): LocalFirstOutcome = session.ask(question)

        override fun close() {
            scope.cancel()
            session.close()
            resources?.close()
        }
    }

    /** No helper on this host. Nothing is bound, and the CLI behaves exactly as it did before the helper existed. */
    data class Unbound(val reason: String) : OnDeviceAssistant {
        override fun close() = Unit
    }

    companion object {
        /**
         * Bind the helper [locator] finds, or say why there is none. Spawns nothing: the helper
         * starts on the first probe.
         *
         * @param eventApiFactory The CLI's door factory, `EnvironmentService::createEventApi`.
         */
        internal fun bind(
            eventApiFactory: (AgentId) -> AgentEventApi,
            locator: AmpereHelperLocator = AmpereHelperLocator(),
        ): OnDeviceAssistant = when (val found = OnDeviceHelperEngine.locate(locator)) {
            is OnDeviceHelperEngine.Absent -> Unbound(found.reason)
            is OnDeviceHelperEngine.Present -> Bound(
                engine = found.engine,
                eventApi = eventApiFactory(OnDeviceInferenceSession.AGENT_ID),
                resources = found,
            )
        }
    }
}
