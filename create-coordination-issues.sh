#!/bin/bash

# AMPERE Coordination Visualization Epic - GitHub Issue Creation Script
# Run this from a terminal where you're authenticated with `gh`
# Usage: ./create-coordination-issues.sh

set -e

REPO="socket-link/ampere"

echo "Creating Coordination Visualization Epic and Tasks..."
echo "Repository: $REPO"
echo ""

# Create the Epic issue first
echo "Creating Epic: CLI Coordination Visualization..."

EPIC_URL=$(gh issue create \
  --repo "$REPO" \
  --title "CLI Coordination Visualization" \
  --label "feature" \
  --body "## Context

AMPERE's multi-agent coordination is currently invisible. While the dashboard shows individual agent states and the event stream shows individual events, there's no way to observe the relationships and interactions between agents. This makes it impossible to debug coordination failures, demonstrate collaborative capabilities, or verify that agents are actually working together rather than in parallel.

This epic adds a Coordination View mode to the CLI that visualizes the topology of agent interactions, tracks active collaborative activities, and provides a filtered feed of inter-agent events. The visualization follows the biological metaphor—showing the \"connectome\" of the agent nervous system rather than individual neuron firings.

## Objective

Create a new CLI mode (\`c\` key) that visualizes multi-agent coordination through a topology graph, active coordination panel, and interaction feed. Enable developers to see collaboration happening in real-time, debug coordination failures, and demonstrate AMPERE's multi-agent capabilities.

## Expected Outcomes

- Pressing \`c\` in the CLI enters Coordination View
- Topology graph shows agents as nodes with edges representing recent interactions
- Active coordination panel shows in-progress meetings, pending handoffs, and blocked agents
- Interaction feed shows only inter-agent events with clear source → destination formatting
- Sub-modes allow focusing on topology (\`t\`), statistics (\`s\`), feed (\`f\`), or meeting details (\`m\`)
- The visualization updates in real-time as coordination events flow through the system

## Technical Constraints

- Follow existing CLI patterns (presenter/renderer separation, mode switching)
- No changes to agent implementations—observe existing events
- ASCII rendering that works across terminals (no special Unicode requirements beyond basic box drawing)
- Must handle case where no coordination is happening (empty state)

## Subtasks

1. AMP-301.1: Define Coordination Event Types and Tracker Service
2. AMP-301.2: Implement Interaction Type Classification
3. AMP-301.3: Create Topology Graph Data Model and Layout Algorithm
4. AMP-301.4: Implement Coordination Presenter and State Management
5. AMP-301.5: Implement Topology Graph Renderer
6. AMP-301.6: Implement Active Coordination Panel Renderer
7. AMP-301.7: Implement Interaction Feed Renderer
8. AMP-301.8: Implement Statistics Sub-mode
9. AMP-301.9: Integration and Mode Switching

## Success Criteria

1. **Visibility**: When agents coordinate, you can see it happening in real-time
2. **Debugging**: When coordination fails, you can identify where and why
3. **Demo-ready**: A casual observer watching the screen understands that agents are collaborating
4. **Scalable**: Works with 3 agents (current) and would scale to 10+ agents
5. **Non-intrusive**: Tracking coordination doesn't require changes to agent implementations—it observes existing events")

echo "Epic created: $EPIC_URL"
echo ""

# Extract issue number from URL for referencing
EPIC_NUM=$(echo "$EPIC_URL" | grep -oE '[0-9]+$')

# Task 1: Define Coordination Event Types and Tracker Service
echo "Creating Task 1: Define Coordination Event Types and Tracker Service..."

TASK1_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.1: Define Coordination Event Types and Tracker Service" \
  --label "task" \
  --body "## Context

Before we can visualize coordination, we need to track it. This task creates the data model for coordination events and a service that observes the EventSerializerBus to identify and track inter-agent interactions.

**Epic**: #$EPIC_NUM

## Objective

Create the foundational types for coordination tracking and a service that maintains current coordination state by observing events.

## Implementation Details

**File: \`ampere-core/src/commonMain/kotlin/link/socket/ampere/coordination/CoordinationTypes.kt\`**

Create the following types:
- \`InteractionType\` enum (TICKET_ASSIGNED, CLARIFICATION_REQUEST, CLARIFICATION_RESPONSE, REVIEW_REQUEST, REVIEW_COMPLETE, MEETING_INVITE, MEETING_MESSAGE, HELP_REQUEST, HELP_RESPONSE, DELEGATION, HUMAN_ESCALATION, HUMAN_RESPONSE)
- \`AgentInteraction\` data class representing a single interaction between two agents
- \`CoordinationEdge\` data class representing an edge in the topology graph
- \`PendingHandoff\` data class for tracking work handoffs between agents
- \`BlockedAgent\` data class for agents waiting on others
- \`CoordinationState\` data class as a snapshot of current coordination state
- \`ActiveMeeting\` data class for in-progress meetings

**File: \`ampere-core/src/commonMain/kotlin/link/socket/ampere/coordination/CoordinationTracker.kt\`**

Create a service that:
- Subscribes to EventSerializerBus
- Maintains interaction history (last 1000 interactions)
- Builds coordination edges from interaction patterns
- Exposes \`state: StateFlow<CoordinationState>\` for reactive updates
- Provides \`getStatistics()\` for aggregate metrics

## Validation

1. Create a test that instantiates \`CoordinationTracker\` with a mock event bus
2. Verify that \`state\` flow emits initial empty state
3. Verify that \`getStatistics()\` returns zeroed statistics initially
4. Verify that the types compile and represent the coordination concepts correctly

## Test Specification

\`\`\`kotlin
class CoordinationTrackerTest {
    @Test
    fun \`initial state is empty\`() {
        val tracker = CoordinationTracker(mockEventBus)
        val state = tracker.state.value

        assertTrue(state.edges.isEmpty())
        assertTrue(state.activeMeetings.isEmpty())
        assertTrue(state.pendingHandoffs.isEmpty())
    }

    @Test
    fun \`statistics returns zero counts initially\`() {
        val tracker = CoordinationTracker(mockEventBus)
        val stats = tracker.getStatistics()

        assertEquals(0, stats.totalInteractions)
        assertEquals(0, stats.uniqueAgentPairs)
    }
}
\`\`\`")

echo "Task 1 created: $TASK1_URL"
TASK1_NUM=$(echo "$TASK1_URL" | grep -oE '[0-9]+$')

# Task 2: Implement Interaction Type Classification
echo "Creating Task 2: Implement Interaction Type Classification..."

TASK2_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.2: Implement Interaction Type Classification" \
  --label "task" \
  --body "## Context

The CoordinationTracker needs to identify which events represent inter-agent coordination. This task implements the classification logic that examines events and extracts coordination semantics.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK1_NUM

## Objective

Implement the \`classifyAsInteraction\` function that examines events flowing through the EventSerializerBus and identifies those that represent coordination between agents.

## Implementation Details

**File: \`ampere-core/src/commonMain/kotlin/link/socket/ampere/coordination/InteractionClassifier.kt\`**

Create a classifier that:
- Takes an \`Event\` and returns \`AgentInteraction?\` (null if not a coordination event)
- Recognizes ticket assignments as TICKET_ASSIGNED
- Recognizes thread messages with @mentions as CLARIFICATION_REQUEST or CLARIFICATION_RESPONSE
- Recognizes meeting events as MEETING_INVITE or MEETING_MESSAGE
- Recognizes review events as REVIEW_REQUEST or REVIEW_COMPLETE
- Recognizes help requests between agents
- Recognizes human escalation and response events
- Extracts source agent, target agent, and summary from each event

Key methods:
- \`classify(event: Event): AgentInteraction?\`
- \`extractMention(content: String): AgentId?\` for parsing @mentions
- \`truncate(text: String, maxLength: Int): String\` for summary generation

Update \`CoordinationTracker\` to use the classifier in its event processing.

## Validation

1. Create test events of each type and verify classification
2. Verify that non-coordination events (like KnowledgeStored) return null
3. Verify that @mentions are extracted correctly
4. Verify that summary truncation works

## Test Specification

\`\`\`kotlin
class InteractionClassifierTest {
    private val classifier = InteractionClassifier()

    @Test
    fun \`ticket assignment is classified correctly\`() {
        val event = TicketAssignedEvent(
            ticketId = TicketId(\"AMPERE-47\"),
            assignedBy = AgentId(\"ProductManagerAgent\"),
            assignedTo = AgentId(\"CodeWriterAgent\"),
            timestamp = Clock.System.now()
        )

        val interaction = classifier.classify(event)

        assertNotNull(interaction)
        assertEquals(InteractionType.TICKET_ASSIGNED, interaction.interactionType)
    }

    @Test
    fun \`knowledge stored event is not classified as coordination\`() {
        val event = KnowledgeStoredEvent(...)
        val interaction = classifier.classify(event)
        assertNull(interaction)
    }
}
\`\`\`

## Note

Adapt event type names to match your actual event class hierarchy. The classifier pattern works regardless of specific naming.")

echo "Task 2 created: $TASK2_URL"
TASK2_NUM=$(echo "$TASK2_URL" | grep -oE '[0-9]+$')

# Task 3: Create Topology Graph Data Model and Layout Algorithm
echo "Creating Task 3: Create Topology Graph Data Model and Layout Algorithm..."

TASK3_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.3: Create Topology Graph Data Model and Layout Algorithm" \
  --label "task" \
  --body "## Context

The topology graph needs to render agents as nodes and their relationships as edges in an ASCII format. This requires a layout algorithm that positions nodes sensibly and a data model that the renderer can use.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK1_NUM

## Objective

Create the data structures and layout algorithm that transform \`CoordinationState\` into renderable graph positions.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/TopologyGraph.kt\`**

Create the following types:
- \`NodePosition(x: Int, y: Int)\` - character cell coordinates
- \`TopologyNode(agentId, displayName, state, position, isHuman)\`
- \`TopologyEdge(source, target, label, isActive, isBidirectional)\`
- \`TopologyLayout(nodes, edges, width, height)\`

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/TopologyLayoutCalculator.kt\`**

Create a layout calculator that:
- Ranks agents by interaction count (most connected = more central)
- Positions nodes in a grid layout
- Places Human node at bottom when present
- Detects bidirectional edges
- Shortens agent names for display (remove \"Agent\" suffix)
- Summarizes interaction types for edge labels

Configuration:
- \`nodeWidth = 15\`
- \`nodeHeight = 3\`
- \`horizontalSpacing = 8\`
- \`verticalSpacing = 4\`

## Validation

1. Create a test with 3 agents and 2 edges, verify layout produces valid positions
2. Verify that Human agent is positioned at bottom
3. Verify that agent names are shortened correctly
4. Verify that bidirectional edges are detected

## Test Specification

\`\`\`kotlin
class TopologyLayoutCalculatorTest {
    private val calculator = TopologyLayoutCalculator()

    @Test
    fun \`three agents are laid out in grid\`() {
        val state = createStateWith3Agents()
        val agentStates = mapOf(...)

        val layout = calculator.calculateLayout(state, agentStates)

        assertEquals(3, layout.nodes.size)
        assertTrue(layout.nodes.all { it.position.x >= 0 && it.position.y >= 0 })
    }

    @Test
    fun \`human agent positioned at bottom\`() {
        val state = createStateWithHumanEscalation()
        val layout = calculator.calculateLayout(state, agentStates)

        val humanNode = layout.nodes.find { it.isHuman }
        val otherNodes = layout.nodes.filter { !it.isHuman }

        assertTrue(otherNodes.all { humanNode!!.position.y > it.position.y })
    }
}
\`\`\`")

echo "Task 3 created: $TASK3_URL"
TASK3_NUM=$(echo "$TASK3_URL" | grep -oE '[0-9]+$')

# Task 4: Implement Coordination Presenter and State Management
echo "Creating Task 4: Implement Coordination Presenter and State Management..."

TASK4_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.4: Implement Coordination Presenter and State Management" \
  --label "task" \
  --body "## Context

The presenter follows the existing CLI pattern—collecting state from various sources and preparing it for rendering. This presenter subscribes to the CoordinationTracker and agent state, combining them into a view state.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK1_NUM, #$TASK2_NUM, #$TASK3_NUM

## Objective

Create \`CoordinationPresenter\` that manages state for the Coordination View, including sub-mode switching and reactive updates.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/CoordinationPresenter.kt\`**

Create:

\`\`\`kotlin
enum class CoordinationSubMode {
    TOPOLOGY,    // Full topology graph
    FEED,        // Full interaction feed
    STATISTICS,  // Coordination statistics
    MEETING      // Meeting detail view
}

data class CoordinationViewState(
    val subMode: CoordinationSubMode,
    val layout: TopologyLayout,
    val coordinationState: CoordinationState,
    val statistics: CoordinationStatistics?,
    val selectedMeetingId: String?,
    val verbose: Boolean,
    val focusedAgentId: AgentId?
)
\`\`\`

The presenter should:
- Combine \`CoordinationTracker.state\` with agent states using \`combine()\`
- Calculate layout using \`TopologyLayoutCalculator\`
- Expose \`viewState: StateFlow<CoordinationViewState>\`
- Provide action methods: \`switchToTopology()\`, \`switchToFeed()\`, \`switchToStatistics()\`, \`switchToMeetingDetail(meetingId)\`, \`toggleVerbose()\`, \`focusAgent(agentId)\`, \`focusAgentByIndex(index)\`

Create interface:
\`\`\`kotlin
interface AgentStateProvider {
    val agentStates: StateFlow<Map<AgentId, String>>
}
\`\`\`

## Validation

1. Create presenter with mock tracker and agent state provider
2. Verify initial view state has TOPOLOGY sub-mode
3. Verify sub-mode switching updates view state
4. Verify verbose toggle works
5. Verify agent focus by index works

## Test Specification

\`\`\`kotlin
class CoordinationPresenterTest {
    @Test
    fun \`initial state has TOPOLOGY sub-mode\`() = runTest {
        val presenter = createPresenter()
        assertEquals(CoordinationSubMode.TOPOLOGY, presenter.viewState.value.subMode)
    }

    @Test
    fun \`switchToStatistics changes sub-mode and loads stats\`() = runTest {
        val presenter = createPresenter()
        presenter.switchToStatistics()

        assertEquals(CoordinationSubMode.STATISTICS, presenter.viewState.value.subMode)
        assertNotNull(presenter.viewState.value.statistics)
    }
}
\`\`\`")

echo "Task 4 created: $TASK4_URL"
TASK4_NUM=$(echo "$TASK4_URL" | grep -oE '[0-9]+$')

# Task 5: Implement Topology Graph Renderer
echo "Creating Task 5: Implement Topology Graph Renderer..."

TASK5_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.5: Implement Topology Graph Renderer" \
  --label "task" \
  --body "## Context

The topology renderer draws the ASCII network graph showing agents as boxes and their relationships as lines. This is the visual centerpiece of the Coordination View.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK3_NUM, #$TASK4_NUM

## Objective

Create a renderer that transforms \`TopologyLayout\` into a renderable ASCII string with boxes, edges, labels, and state indicators.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/TopologyRenderer.kt\`**

The renderer should:
- Draw agent nodes as ASCII boxes (15 chars wide, 4 lines tall)
- Show agent name and state inside each box
- Animate spinner character based on tick parameter (◐◓◑◒)
- Color boxes by state (green=working, red=blocked, cyan=meeting, white=idle)
- Draw edges between connected agents using line characters (─, │, ┌, └)
- Use solid lines (───) for active edges (< 60s) and dashed (╌╌╌) for recent (< 5m)
- Show edge labels above the connecting lines
- Add legend at bottom explaining line styles

Create helper class \`CharBuffer\` for 2D ASCII composition:
\`\`\`kotlin
class CharBuffer(width: Int, height: Int) {
    fun write(x: Int, y: Int, text: String)
    override fun toString(): String
}
\`\`\`

Example output:
\`\`\`
┌─────────────┐         ticket          ┌─────────────┐
│ ProductMgr  │ ───────────────────────▶│ CodeWriter  │
│   ◐ idle    │                         │  ◑ working  │
└─────────────┘                         └─────────────┘

─── active (< 60s)   ╌╌╌ recent (< 5m)   dim: historical
\`\`\`

## Validation

1. Render a layout with 2 connected nodes, verify boxes appear correctly
2. Verify spinner character changes with tick parameter
3. Verify state colors are applied (working=green, blocked=red)
4. Verify edges connect nodes with appropriate styling
5. Verify empty state shows helpful message

## Test Specification

\`\`\`kotlin
class TopologyRendererTest {
    @Test
    fun \`renders empty state message when no nodes\`() {
        val layout = TopologyLayout(emptyList(), emptyList(), 0, 0)
        val output = TopologyRenderer().render(layout)
        assertTrue(output.contains(\"No agent interactions\"))
    }

    @Test
    fun \`renders node with correct name and state\`() {
        val layout = createLayoutWithOneNode(\"CodeWriter\", \"working\")
        val output = TopologyRenderer().render(layout)
        assertTrue(output.contains(\"CodeWriter\"))
        assertTrue(output.contains(\"working\"))
    }
}
\`\`\`")

echo "Task 5 created: $TASK5_URL"
TASK5_NUM=$(echo "$TASK5_URL" | grep -oE '[0-9]+$')

# Task 6: Implement Active Coordination Panel Renderer
echo "Creating Task 6: Implement Active Coordination Panel Renderer..."

TASK6_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.6: Implement Active Coordination Panel Renderer" \
  --label "task" \
  --body "## Context

The active coordination panel shows what's happening right now: meetings in progress, pending handoffs, blocked agents. This is the \"situation awareness\" section.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK4_NUM

## Objective

Create a renderer for the middle panel showing active meetings, pending handoffs, and blocked agents.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/ActiveCoordinationRenderer.kt\`**

The renderer should:
- Take \`CoordinationState\` and \`maxLines\` parameter
- Render meetings with cyan ▶ prefix, name in quotes, participants, duration, message count
- Render pending handoffs with yellow ⧖ prefix, source → target, ticket ID, waiting reason
- Render blocked agents with red ⛔ prefix, agent name, waiting for whom, reason, duration
- Show \"No active coordination\" message when empty
- Limit output to maxLines with \"... and N more\" overflow indicator

Helper methods:
- \`formatDuration(Duration): String\` - returns \"5s\", \"2m\", \"1h 30m\"
- \`shortenAgentName(String): String\` - removes \"Agent\" suffix

Example output:
\`\`\`
▶ Meeting: \"MCP Architecture Discussion\"
    Participants: ProductMgr, CodeWriter    Started: 2m ago    Messages: 4

⧖ Pending Handoff: CodeWriter → QA
    Ticket: AMPERE-47    Waiting: review requested (45s)
\`\`\`

## Validation

1. Render state with one meeting, verify output shows meeting name and participants
2. Render state with pending handoff, verify source → target formatting
3. Render empty state, verify \"No active coordination\" message appears
4. Verify duration formatting works for seconds, minutes, hours")

echo "Task 6 created: $TASK6_URL"
TASK6_NUM=$(echo "$TASK6_URL" | grep -oE '[0-9]+$')

# Task 7: Implement Interaction Feed Renderer
echo "Creating Task 7: Implement Interaction Feed Renderer..."

TASK7_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.7: Implement Interaction Feed Renderer" \
  --label "task" \
  --body "## Context

The interaction feed shows the stream of inter-agent events. It's similar to the event stream but filtered to only coordination-relevant events.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK4_NUM

## Objective

Create a renderer that shows recent agent interactions with source → target formatting and verbose mode toggle.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/InteractionFeedRenderer.kt\`**

The renderer should:
- Take list of \`AgentInteraction\`, verbose flag, and maxLines
- Format each interaction as: \`HH:MM:SS  SourceAgent  ──▶  TargetAgent  type  summary\`
- Use ◀── arrow for response types (clarification_response, help_response, review_complete, human_response)
- Color-code by interaction type:
  - Green: ticket assignments
  - Cyan: meeting events
  - Red: human escalation
  - Yellow: human response
  - Magenta: review events
  - White: other
- Truncate summary to 30 chars in normal mode
- Show full summary on second line in verbose mode
- Show \"No recent interactions\" when empty

Example output:
\`\`\`
14:23:45  ProductMgr   ──▶  CodeWriter   ticket         Assigned: Implement MCP...
14:24:12  CodeWriter   ──▶  ProductMgr   clarify?       OAuth scope question
14:24:30  ProductMgr   ◀──  CodeWriter   response       Read-only access
\`\`\`

## Validation

1. Render list of interactions, verify time/source/target formatting
2. Verify arrow direction changes for response types
3. Verify verbose mode shows full summaries
4. Verify color coding by interaction type")

echo "Task 7 created: $TASK7_URL"
TASK7_NUM=$(echo "$TASK7_URL" | grep -oE '[0-9]+$')

# Task 8: Implement Statistics Sub-mode
echo "Creating Task 8: Implement Statistics Sub-mode..."

TASK8_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.8: Implement Statistics Sub-mode" \
  --label "task" \
  --body "## Context

The statistics view provides aggregate metrics about coordination patterns—useful for understanding overall system health and identifying bottlenecks.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK1_NUM, #$TASK4_NUM

## Objective

Create a renderer for the statistics sub-mode showing coordination metrics and interaction matrix.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/StatisticsRenderer.kt\`**

The renderer should display:

1. **Summary Statistics**
   - Total interactions (session)
   - Unique agent pairs
   - Most active pair with count
   - Average response time (if trackable)
   - Meetings held count
   - Successful/failed handoffs
   - Human escalations count

2. **Interaction Matrix**
   - Row and column headers are agent names (shortened)
   - Cells show interaction count from row agent to column agent
   - Diagonal shows \"-\" (agent can't interact with itself)
   - Zero counts are dimmed

Example output:
\`\`\`
COORDINATION STATISTICS (session)
────────────────────────────────────────
Total interactions:     47
Unique agent pairs:     4/6 possible

INTERACTION MATRIX
                 ProductMgr  CodeWriter  QA    Human
ProductMgr            -          15      2      1
CodeWriter           12           -      8      1
QA                    2           6      -      0
Human                 1           1      0      -
\`\`\`

## Validation

1. Render statistics with interaction data, verify counts display correctly
2. Verify matrix shows agent names as row/column headers
3. Verify diagonal shows \"-\"
4. Verify zero counts are dimmed")

echo "Task 8 created: $TASK8_URL"
TASK8_NUM=$(echo "$TASK8_URL" | grep -oE '[0-9]+$')

# Task 9: Integration and Mode Switching
echo "Creating Task 9: Integration and Mode Switching..."

TASK9_URL=$(gh issue create \
  --repo "$REPO" \
  --title "AMP-301.9: Integration and Mode Switching" \
  --label "task" \
  --body "## Context

All the pieces exist—now they need to be wired together into the main CLI with mode switching.

**Epic**: #$EPIC_NUM
**Depends On**: #$TASK5_NUM, #$TASK6_NUM, #$TASK7_NUM, #$TASK8_NUM

## Objective

Integrate the Coordination View into the existing CLI infrastructure, add the \`c\` key binding, and implement sub-mode switching.

## Implementation Details

**File: \`ampere-cli/src/jvmMain/kotlin/link/socket/ampere/cli/coordination/CoordinationView.kt\`**

Create the main view that composes all sub-renderers:

\`\`\`kotlin
class CoordinationView(
    private val presenter: CoordinationPresenter,
    private val topologyRenderer: TopologyRenderer,
    private val activeCoordinationRenderer: ActiveCoordinationRenderer,
    private val interactionFeedRenderer: InteractionFeedRenderer,
    private val statisticsRenderer: StatisticsRenderer
) {
    fun render(terminalWidth: Int, terminalHeight: Int): String
    fun handleKey(key: Char): Boolean  // returns false to exit view
}
\`\`\`

The view should:
- Render appropriate content based on current sub-mode
- Show header with view name and status (verbose indicator)
- Show section dividers between panels
- Show footer with keybindings: \`[t]opology  [f]eed  [s]tats  [m]eeting  [v]erbose  [1-9] agent  [q] exit\`
- Handle keyboard input for mode switching
- Animate topology spinner using tick counter

**Integration into main CLI:**

Add \`c\` key binding in the main CLI controller to enter coordination view:
\`\`\`kotlin
when (key) {
    'c' -> enterCoordinationView()
    // ... other modes
}

private fun enterCoordinationView() {
    val coordinationView = CoordinationView(coordinationPresenter)
    while (true) {
        clearScreen()
        print(coordinationView.render(terminalWidth, terminalHeight))
        val key = readKey()
        if (!coordinationView.handleKey(key)) break
    }
}
\`\`\`

## Validation

1. Press \`c\` from main CLI, verify Coordination View appears
2. Press \`t\`, \`f\`, \`s\` to switch between sub-modes
3. Press \`v\` to toggle verbose mode
4. Press \`q\` to exit back to main CLI
5. Verify that coordination events appear in real-time as agents interact

## Integration Test

Create a test scenario:
1. Start AMPERE with three agents
2. Create a ticket assigned to CodeWriterAgent
3. Open CLI and press \`c\` for Coordination View
4. Verify that the ticket assignment appears in the interaction feed
5. Verify that an edge appears between ProductManager and CodeWriter in the topology

## Success Criteria

After this task, pressing \`c\` in the CLI shows a complete coordination visualization with:
- Live-updating topology graph
- Active coordination panel
- Interaction feed
- Working sub-mode switching
- Keyboard navigation")

echo "Task 9 created: $TASK9_URL"
TASK9_NUM=$(echo "$TASK9_URL" | grep -oE '[0-9]+$')

echo ""
echo "============================================"
echo "All issues created successfully!"
echo "============================================"
echo ""
echo "Epic: $EPIC_URL"
echo ""
echo "Tasks:"
echo "  1. Define Coordination Event Types: $TASK1_URL"
echo "  2. Implement Interaction Classification: $TASK2_URL"
echo "  3. Create Topology Layout Algorithm: $TASK3_URL"
echo "  4. Implement Coordination Presenter: $TASK4_URL"
echo "  5. Implement Topology Graph Renderer: $TASK5_URL"
echo "  6. Implement Active Coordination Panel: $TASK6_URL"
echo "  7. Implement Interaction Feed Renderer: $TASK7_URL"
echo "  8. Implement Statistics Sub-mode: $TASK8_URL"
echo "  9. Integration and Mode Switching: $TASK9_URL"
echo ""
echo "Dependency graph:"
echo "  Task 1 ──┬──▶ Task 2 ──┐"
echo "           │             │"
echo "           ├──▶ Task 3 ──┼──▶ Task 4 ──┬──▶ Task 5 ──┐"
echo "           │             │             │             │"
echo "           │             │             ├──▶ Task 6 ──┼──▶ Task 9"
echo "           │             │             │             │"
echo "           │             │             ├──▶ Task 7 ──┤"
echo "           │             │             │             │"
echo "           └─────────────┴─────────────┴──▶ Task 8 ──┘"
echo ""
