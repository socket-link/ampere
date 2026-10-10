package link.socket.ampere.roster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * AMPR-413: `<seat>/<tool>` ids, so two seats can share one tool and a plan step
 * still says which seat runs it.
 */
class SeatToolTest {

    private val json = Json { encodeDefaults = true }

    private val scout = RoleConfig(
        id = RoleId("scout"),
        title = "Scout",
        instructions = PromptRef("consumer.scout", 1),
        tools = setOf(RosterTools.WEB_SEARCH, RosterTools.WEB_FETCH),
    )

    private val inspector = RoleConfig(
        id = RoleId("inspector"),
        title = "Inspector",
        instructions = PromptRef("consumer.inspector", 1),
        tools = setOf(RosterTools.WEB_SEARCH),
        reviews = setOf(scout.id),
    )

    private val roster = RosterConfig(
        host = scout.id,
        roles = listOf(scout, inspector),
        verifier = inspector.id,
    )

    @Test
    fun `two seats sharing one tool hold two distinct ids`() {
        val byScout = SeatTool(scout.id, RosterTools.WEB_SEARCH)
        val byInspector = SeatTool(inspector.id, RosterTools.WEB_SEARCH)

        assertEquals("scout/web_search", byScout.id)
        assertEquals("inspector/web_search", byInspector.id)
        assertNotEquals(byScout.id, byInspector.id)
        assertEquals(byScout.tool, byInspector.tool, "one tool, two seats")
    }

    @Test
    fun `a qualified id parses back to the seat and the tool that built it`() {
        val seatTool = SeatTool(scout.id, RosterTools.WEB_SEARCH)

        assertEquals(seatTool, SeatTool.parse(seatTool.id))
    }

    @Test
    fun `a bare tool id names no seat`() {
        assertNull(SeatTool.parse(RosterTools.WEB_SEARCH))
    }

    @Test
    fun `a tool id carrying a separator of its own arrives whole`() {
        val parsed = SeatTool.parse("scout/github/issues/create")

        assertEquals(RoleId("scout"), parsed?.seat)
        assertEquals("github/issues/create", parsed?.tool)
    }

    @Test
    fun `a half-written id names neither`() {
        assertNull(SeatTool.parse("/web_search"), "no seat")
        assertNull(SeatTool.parse("scout/"), "no tool")
        assertNull(SeatTool.parse("/"), "neither")
        assertNull(SeatTool.parse(""), "nothing at all")
    }

    @Test
    fun `a seat's tools are each namespaced to it`() {
        assertEquals(
            setOf("scout/web_search", "scout/web_fetch"),
            scout.seatTools().mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(emptySet<SeatTool>(), RoleConfig(RoleId("mute"), "Mute", PromptRef("m", 1)).seatTools())
    }

    @Test
    fun `the roster says which seat runs a qualified id`() {
        assertEquals(scout, roster.seatRunning("scout/web_search"))
        assertEquals(inspector, roster.seatRunning("inspector/web_search"))
        assertEquals(scout, roster.seatRunning("scout/web_fetch"))
    }

    @Test
    fun `no seat runs a tool its seat never declared`() {
        assertNull(roster.seatRunning("inspector/web_fetch"), "the Inspector declared only web_search")
    }

    @Test
    fun `no seat runs an id naming a seat the roster does not hold`() {
        assertNull(roster.seatRunning("ghost/web_search"))
    }

    @Test
    fun `no seat runs a bare id`() {
        assertNull(roster.seatRunning(RosterTools.WEB_SEARCH), "a bare id says what to run and not who runs it")
    }

    @Test
    fun `a seat tool round-trips through json`() {
        val seatTool = SeatTool.of(seat = "scout", tool = RosterTools.WEB_SEARCH)

        val encoded = json.encodeToString(SeatTool.serializer(), seatTool)
        val decoded = json.decodeFromString(SeatTool.serializer(), encoded)

        assertEquals(seatTool, decoded)
        assertEquals("scout/web_search", decoded.id)
    }
}
