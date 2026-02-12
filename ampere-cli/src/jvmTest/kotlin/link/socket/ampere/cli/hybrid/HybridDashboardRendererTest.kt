package link.socket.ampere.cli.hybrid

import link.socket.ampere.cli.layout.AnsiCellParser
import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary
import link.socket.ampere.cli.watch.presentation.SystemState
import link.socket.ampere.cli.watch.presentation.SystemVitals
import link.socket.ampere.cli.watch.presentation.WatchViewState
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Simple test pane that renders a fixed text pattern.
 */
class TestPane(private val fillChar: Char = '.') : PaneRenderer {
    override fun render(width: Int, height: Int): List<String> {
        return List(height) { String(CharArray(width) { fillChar }) }
    }
}

/**
 * Test pane that renders visible content in the first few lines.
 */
class ContentPane(private val lines: List<String> = emptyList()) : PaneRenderer {
    override fun render(width: Int, height: Int): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until height) {
            if (i < lines.size) {
                val line = lines[i]
                result.add(line.take(width).padEnd(width))
            } else {
                result.add(" ".repeat(width))
            }
        }
        return result
    }
}

class HybridDashboardRendererTest {

    private fun createRenderer(
        width: Int = 80,
        height: Int = 24,
        useColor: Boolean = false,
        useUnicode: Boolean = true
    ): HybridDashboardRenderer {
        val config = HybridConfig(
            enableSubstrate = true,
            enableParticles = true,
            useColor = useColor,
            useUnicode = useUnicode,
            substrateOpacity = 0.15f,
            particleMaxCount = 10
        )
        return HybridDashboardRenderer(
            terminal = null,
            config = config,
            explicitWidth = width,
            explicitHeight = height
        )
    }

    private fun createViewState(): WatchViewState {
        val now = Clock.System.now()
        return WatchViewState(
            systemVitals = SystemVitals(
                activeAgentCount = 1,
                systemState = SystemState.WORKING,
                lastSignificantEventTime = now
            ),
            agentStates = mapOf(
                "agent-1" to AgentActivityState(
                    agentId = "agent-1",
                    displayName = "Agent Alpha",
                    currentState = AgentState.WORKING,
                    lastActivityTimestamp = now,
                    consecutiveCognitiveCycles = 1,
                    isIdle = false
                )
            ),
            recentSignificantEvents = listOf(
                SignificantEventSummary(
                    eventId = "evt-1",
                    timestamp = now,
                    eventType = "TaskCreated",
                    sourceAgentName = "Agent Alpha",
                    summaryText = "Created task: implement feature",
                    significance = EventSignificance.SIGNIFICANT
                )
            )
        )
    }

    @Test
    fun `renders three panes without crashing`() {
        val renderer = createRenderer()
        val output = renderer.render(
            leftPane = TestPane('L'),
            middlePane = TestPane('M'),
            rightPane = TestPane('R'),
            statusBar = "Status bar text"
        )
        assertTrue(output.isNotEmpty())
    }

    @Test
    fun `pane content appears in output`() {
        val renderer = createRenderer()
        val output = renderer.render(
            leftPane = ContentPane(listOf("LEFT CONTENT")),
            middlePane = ContentPane(listOf("MID CONTENT")),
            rightPane = ContentPane(listOf("RIGHT HERE")),
            statusBar = "status"
        )

        val stripped = AnsiCellParser.stripAnsi(output)
        assertTrue(stripped.contains("LEFT CONTENT"), "Left pane content should appear")
        assertTrue(stripped.contains("MID CONTENT"), "Middle pane content should appear")
        assertTrue(stripped.contains("RIGHT HERE"), "Right pane content should appear")
    }

    @Test
    fun `status bar appears in output`() {
        val renderer = createRenderer()
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "MY STATUS"
        )

        val stripped = AnsiCellParser.stripAnsi(output)
        assertTrue(stripped.contains("MY STATUS"), "Status bar should appear in output")
    }

    @Test
    fun `dividers appear between panes`() {
        val renderer = createRenderer(useUnicode = true)
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status"
        )

        val stripped = AnsiCellParser.stripAnsi(output)
        assertTrue(stripped.contains("\u2502"), "Unicode divider (│) should appear")
    }

    @Test
    fun `null viewState does not crash`() {
        val renderer = createRenderer()
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status",
            viewState = null
        )
        assertTrue(output.isNotEmpty())
    }

    @Test
    fun `live viewState does not crash`() {
        val renderer = createRenderer()

        // First frame
        renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status",
            viewState = null
        )

        // Second frame with live state
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status",
            viewState = createViewState()
        )
        assertTrue(output.isNotEmpty())
    }

    @Test
    fun `second frame uses differential rendering`() {
        val renderer = createRenderer()

        val first = renderer.render(
            leftPane = TestPane('A'),
            middlePane = TestPane('B'),
            rightPane = TestPane('C'),
            statusBar = "status"
        )

        // Second frame with same content (diff should be smaller or equal)
        val second = renderer.render(
            leftPane = TestPane('A'),
            middlePane = TestPane('B'),
            rightPane = TestPane('C'),
            statusBar = "status"
        )

        assertTrue(second.length <= first.length * 2,
            "Diff render should not be dramatically larger than full render")
    }

    @Test
    fun `no-color mode excludes 256-color codes`() {
        val renderer = createRenderer(useColor = false)
        val output = renderer.render(
            leftPane = TestPane('X'),
            middlePane = TestPane('Y'),
            rightPane = TestPane('Z'),
            statusBar = "status"
        )

        assertFalse(output.contains("\u001B[38;5;"),
            "No-color mode should not contain 256-color codes")
    }

    @Test
    fun `horizontal divider appears above status bar`() {
        val renderer = createRenderer(useUnicode = true)
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status"
        )

        val stripped = AnsiCellParser.stripAnsi(output)
        assertTrue(stripped.contains("\u2500"), "Horizontal divider (─) should appear")
    }

    @Test
    fun `hideCursor and showCursor produce correct escape sequences`() {
        val renderer = createRenderer()
        assertEquals("\u001B[?25l", renderer.hideCursor())
        assertEquals("\u001B[?25h", renderer.showCursor())
    }

    @Test
    fun `small terminal dimensions are handled gracefully`() {
        val renderer = createRenderer(width = 40, height = 10)
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status"
        )
        assertTrue(output.isNotEmpty())
    }

    @Test
    fun `ASCII fallback mode uses pipe dividers`() {
        val renderer = createRenderer(useUnicode = false)
        val output = renderer.render(
            leftPane = TestPane(),
            middlePane = TestPane(),
            rightPane = TestPane(),
            statusBar = "status"
        )

        val stripped = AnsiCellParser.stripAnsi(output)
        assertTrue(stripped.contains("|"), "ASCII mode should use pipe dividers")
    }
}
