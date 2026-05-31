package link.socket.ampere.agents.domain

typealias TeamId = String
typealias SprintId = String
typealias PRId = String
typealias RunId = String

/**
 * Correlation id for a broader reasoning unit (a perception, a plan, a task)
 * that spans multiple [Event]s. Distinct from [RunId], which scopes a single
 * Arc execution.
 */
typealias WorkflowId = String
