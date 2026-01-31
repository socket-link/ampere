package link.socket.ampere.cli.watch.presentation

/**
 * Categorizes events by their significance for observation and debugging.
 *
 * This follows the principle that the nervous system (EventSerialBus)
 * should fire every event, but the sensory cortex (watch presenter) should
 * filter based on what requires conscious attention.
 */
enum class EventSignificance(val shouldDisplayByDefault: Boolean) {
    /** Requires immediate human attention - escalations, errors, blocking issues */
    CRITICAL(shouldDisplayByDefault = true),

    /** Represents meaningful state changes - task creation, status updates, milestones */
    SIGNIFICANT(shouldDisplayByDefault = true),

    /** Routine cognitive maintenance - knowledge operations, heartbeats, idle processing */
    ROUTINE(shouldDisplayByDefault = false)
}
