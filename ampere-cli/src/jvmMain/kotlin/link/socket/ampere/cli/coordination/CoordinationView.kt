package link.socket.ampere.cli.coordination

/**
 * Main coordination visualization view that composes all sub-renderers.
 *
 * This view provides a complete coordination dashboard with:
 * - Live-updating topology graph
 * - Active coordination panel
 * - Interaction feed
 * - Statistics view
 * - Keyboard navigation between modes
 */
class CoordinationView(
    private val presenter: CoordinationPresenter,
    private val topologyRenderer: TopologyRenderer = TopologyRenderer(),
    private val activeCoordinationRenderer: ActiveCoordinationRenderer = ActiveCoordinationRenderer(),
    private val interactionFeedRenderer: InteractionFeedRenderer = InteractionFeedRenderer(),
    private val statisticsRenderer: StatisticsRenderer = StatisticsRenderer(),
) {
    private var tick: Int = 0

    companion object {
        private const val HEADER_DIVIDER = "════════════════════════════════════════════════════════════════"
        private const val SECTION_DIVIDER = "────────────────────────────────────────────────────────────────"
    }

    /**
     * Render the coordination view.
     *
     * @param terminalWidth Width of terminal in characters
     * @param terminalHeight Height of terminal in lines
     * @return Rendered view as string
     */
    fun render(terminalWidth: Int, terminalHeight: Int): String {
        val viewState = presenter.viewState.value
        val sections = mutableListOf<String>()

        // Header
        sections.add(renderHeader(viewState))
        sections.add(HEADER_DIVIDER)
        sections.add("")

        // Content based on sub-mode
        when (viewState.subMode) {
            CoordinationSubMode.TOPOLOGY -> {
                sections.add(renderTopologyMode(viewState))
            }
            CoordinationSubMode.FEED -> {
                sections.add(renderFeedMode(viewState))
            }
            CoordinationSubMode.STATISTICS -> {
                sections.add(renderStatisticsMode(viewState))
            }
            CoordinationSubMode.MEETING -> {
                sections.add(renderMeetingMode(viewState))
            }
        }

        sections.add("")
        sections.add(SECTION_DIVIDER)

        // Footer with keybindings
        sections.add(renderFooter(viewState))

        // Increment animation tick for next render
        tick++

        return sections.joinToString("\n")
    }

    /**
     * Handle keyboard input.
     *
     * @param key The key pressed
     * @return true to continue, false to exit view
     */
    fun handleKey(key: Char): Boolean {
        return when (key) {
            't', 'T' -> {
                presenter.switchToTopology()
                true
            }
            'f', 'F' -> {
                presenter.switchToFeed()
                true
            }
            's', 'S' -> {
                presenter.switchToStatistics()
                true
            }
            'v', 'V' -> {
                presenter.toggleVerbose()
                true
            }
            in '1'..'9' -> {
                val index = key - '1'
                presenter.focusAgentByIndex(index)
                true
            }
            'c', 'C' -> {
                presenter.clearFocus()
                true
            }
            'q', 'Q' -> {
                false // Exit view
            }
            else -> true // Ignore unknown keys
        }
    }

    /**
     * Render the header showing current mode and status.
     */
    private fun renderHeader(viewState: CoordinationViewState): String {
        val modeName = when (viewState.subMode) {
            CoordinationSubMode.TOPOLOGY -> "Coordination Topology"
            CoordinationSubMode.FEED -> "Interaction Feed"
            CoordinationSubMode.STATISTICS -> "Coordination Statistics"
            CoordinationSubMode.MEETING -> "Meeting Details"
        }

        val statusFlags = buildList {
            if (viewState.verbose) add("VERBOSE")
            viewState.focusedAgentId?.let { add("Focus: $it") }
        }

        return if (statusFlags.isEmpty()) {
            "COORDINATION VIEW - $modeName"
        } else {
            "COORDINATION VIEW - $modeName [${statusFlags.joinToString(", ")}]"
        }
    }

    /**
     * Render the footer with keybindings.
     */
    private fun renderFooter(viewState: CoordinationViewState): String {
        return "[t]opology  [f]eed  [s]tats  [v]erbose  [1-9] agent  [c]lear focus  [q] exit"
    }

    /**
     * Render topology mode: graph + active coordination panel.
     */
    private fun renderTopologyMode(viewState: CoordinationViewState): String {
        val sections = mutableListOf<String>()

        // Topology graph
        sections.add("TOPOLOGY")
        sections.add(SECTION_DIVIDER)
        sections.add(topologyRenderer.render(viewState.layout, tick))
        sections.add("")

        // Active coordination panel
        sections.add("ACTIVE COORDINATION")
        sections.add(SECTION_DIVIDER)
        sections.add(activeCoordinationRenderer.render(viewState.coordinationState, maxLines = 10))

        return sections.joinToString("\n")
    }

    /**
     * Render feed mode: interaction stream.
     */
    private fun renderFeedMode(viewState: CoordinationViewState): String {
        val sections = mutableListOf<String>()

        sections.add("INTERACTION FEED")
        sections.add(SECTION_DIVIDER)

        val interactions = viewState.coordinationState.recentInteractions.takeLast(50)
        sections.add(interactionFeedRenderer.render(interactions, viewState.verbose, maxLines = 30))

        return sections.joinToString("\n")
    }

    /**
     * Render statistics mode: metrics and matrix.
     */
    private fun renderStatisticsMode(viewState: CoordinationViewState): String {
        val statistics = viewState.statistics
            ?: return "Loading statistics..."

        return statisticsRenderer.render(viewState.coordinationState, statistics)
    }

    /**
     * Render meeting details mode.
     */
    private fun renderMeetingMode(viewState: CoordinationViewState): String {
        val meetingId = viewState.selectedMeetingId
            ?: return "No meeting selected"

        val meeting = viewState.coordinationState.activeMeetings.find {
            it.meeting.id == meetingId
        }

        return if (meeting != null) {
            buildString {
                appendLine("MEETING DETAILS")
                appendLine(SECTION_DIVIDER)
                appendLine("Title: ${meeting.meeting.invitation.title}")
                appendLine("Participants: ${meeting.participants.joinToString(", ")}")
                appendLine("Messages: ${meeting.messageCount}")
                appendLine("Status: ${meeting.meeting.status}")
            }
        } else {
            "Meeting not found: $meetingId"
        }
    }
}
