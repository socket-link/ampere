package link.socket.ampere.repl

/**
 * Manages cycling through common event filters during watch.
 */
class EventFilterCycler {
    private val commonFilters = listOf(
        null,  // All events
        "TaskCreated",
        "MessagePosted",
        "TicketStatusChanged",
        "TicketAssigned",
        "QuestionRaised",
        "CodeSubmitted"
    )

    private var currentIndex = 0

    /**
     * Cycle to next filter and return it.
     */
    fun cycle(): String? {
        currentIndex = (currentIndex + 1) % commonFilters.size
        return commonFilters[currentIndex]
    }

    /**
     * Get current filter.
     */
    fun current(): String? = commonFilters[currentIndex]

    /**
     * Get display name for current filter.
     */
    fun currentDisplayName(): String {
        return current() ?: "All Events"
    }

    /**
     * Reset to showing all events.
     */
    fun reset() {
        currentIndex = 0
    }
}
