package link.socket.ampere.agents.domain

typealias TeamId = String
typealias SprintId = String
typealias PRId = String

/**
 * Identity of a single Arc execution (see `AmpereRuntime.execute`). Every
 * event, memory row, and `Emission` produced during that run should carry
 * this id so `link.socket.ampere.trace.ArcTraceProjection` can reconstruct
 * the run. Also aliased as `link.socket.ampere.trace.ArcRunId` for callers
 * in the `trace` package.
 */
typealias RunId = String

/**
 * Correlation id for a broader reasoning unit (a perception, a plan, a task)
 * that spans multiple [link.socket.ampere.agents.domain.event.Event]s.
 *
 * AMPR-240 resolved the historical ambiguity between this and [RunId]: for
 * the duration of one Arc execution, `WorkflowId` collapses into [RunId] —
 * they are the same value. The ambient run id is threaded down as the
 * `workflowId` on `RoutingContext`/telemetry so a run's model invocations
 * are all joinable by a single id. Outside of an Arc execution (a bare
 * perception/plan/task correlation with no enclosing run), `WorkflowId` may
 * still carry a narrower, non-run id. The two typealiases stay distinct
 * because call sites and wire-format fields (`RoutingContext.workflowId`,
 * `ProviderCallStartedEvent.workflowId`, etc.) are named after one or the
 * other — this is a naming/documentation collapse, not a field rename.
 */
typealias WorkflowId = String
