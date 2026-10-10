package link.socket.ampere.roster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.safety.SafetyProbe

/**
 * AMPR-409 (B15): a roster a consumer authors, with no reviewing seat to invent.
 *
 * AMPR-413 adds what such a roster can carry: the reviewing seat as a value rather
 * than an override, a per-seat `ExecutionAssignment`, and the plain-id factories a
 * caller that cannot write a [RoleId] needs.
 */
class RosterConfigTest {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val solo = RoleConfig(
        id = RoleId("solo"),
        title = "Solo",
        instructions = PromptRef("consumer.solo", 1),
        tools = setOf("web_search"),
    )

    private val oneRole = RosterConfig(host = solo.id, roles = listOf(solo))

    @Test
    fun `a one-role roster constructs and is its own host`() {
        assertEquals(listOf(solo), oneRole.all())
        assertEquals(solo.id, oneRole.host)
        assertEquals(solo, oneRole.byId(RoleId("solo")))
        assertNull(oneRole.byId(RoleId("intern")))
    }

    @Test
    fun `a one-role roster reviews nobody and its reviews are acyclic`() {
        assertTrue(oneRole.all().reviewsAreAcyclic())
        assertNull(oneRole.reviewerOf(solo.id))
    }

    @Test
    fun `a roster names no verifier and no resolver by default`() {
        assertNull(oneRole.verifier)
        assertNull(oneRole.resolverFor(ProbeId(SequenceProbe.ID)))
        assertNull(oneRole.resolverFor(ProbeId(SafetyProbe.ID)))
        assertNull(oneRole.resolverFor(ProbeId("blueprint.part-fit")))
    }

    @Test
    fun `a named verifier is the consumer's to override and nothing else is abstract`() {
        val reviewed = RoleConfig(RoleId("maker"), "Maker", PromptRef("consumer.maker", 1))
        val reviewer = RoleConfig(
            id = RoleId("checker"),
            title = "Checker",
            instructions = PromptRef("consumer.checker", 1),
            reviews = setOf(reviewed.id),
        )
        val roster = object : Roster {
            override fun all(): List<RoleConfig> = listOf(reviewed, reviewer)
            override val host: RoleId = reviewed.id
            override val verifier: RoleId = reviewer.id
            override fun resolverFor(probeId: ProbeId): RoleId = reviewed.id
        }

        assertEquals(reviewer.id, roster.verifier)
        assertEquals(reviewed.id, roster.resolverFor(ProbeId("anything")))
        assertEquals(reviewer.id, roster.reviewerOf(reviewed.id))
    }

    @Test
    fun `a duplicated role id is refused`() {
        val twin = solo.copy(title = "Twin")

        val failure = assertFailsWith<IllegalArgumentException> {
            RosterConfig(host = solo.id, roles = listOf(solo, twin))
        }
        assertTrue(failure.message.orEmpty().contains("solo"))
    }

    @Test
    fun `a host who holds no seat is refused`() {
        assertFailsWith<IllegalArgumentException> {
            RosterConfig(host = RoleId("ghost"), roles = listOf(solo))
        }
    }

    @Test
    fun `a cyclic review graph still constructs and is reported by reviewsAreAcyclic`() {
        val a = RoleConfig(RoleId("a"), "A", PromptRef("a", 1), reviews = setOf(RoleId("b")))
        val b = RoleConfig(RoleId("b"), "B", PromptRef("b", 1), reviews = setOf(RoleId("a")))

        val roster = RosterConfig(host = a.id, roles = listOf(a, b))

        assertFalse(roster.all().reviewsAreAcyclic())
    }

    @Test
    fun `a roster round-trips through json`() {
        val maker = RoleConfig(RoleId("maker"), "Maker", PromptRef("consumer.maker", 2), tools = setOf("web_fetch"))
        val checker = RoleConfig(
            id = RoleId("checker"),
            title = "Checker",
            instructions = PromptRef("consumer.checker", 1),
            reviews = setOf(maker.id),
        )
        val roster = RosterConfig(host = maker.id, roles = listOf(maker, checker))

        val encoded = json.encodeToString(RosterConfig.serializer(), roster)
        val decoded = json.decodeFromString(RosterConfig.serializer(), encoded)

        assertEquals(roster, decoded)
        assertTrue(encoded.contains("\"host\":\"maker\""))
        assertNull(decoded.verifier)
    }

    @Test
    fun `a roster decoded with a host who holds no seat is refused`() {
        val encoded =
            """{"host":"ghost","roles":[{"id":"solo","title":"Solo","instructions":{"id":"s","version":1}}]}"""

        // SerializationException is itself an IllegalArgumentException, so pin the message:
        // this must fail the roster's own check, not the decoder's.
        val failure = assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(RosterConfig.serializer(), encoded)
        }
        assertTrue(failure.message.orEmpty().contains("ghost"), failure.message)
    }

    @Test
    fun `a roster can name the seat that reviews without implementing the interface`() {
        val maker = RoleConfig(RoleId("maker"), "Maker", PromptRef("consumer.maker", 1))
        val checker = RoleConfig(
            id = RoleId("checker"),
            title = "Checker",
            instructions = PromptRef("consumer.checker", 1),
            reviews = setOf(maker.id),
        )

        val roster = RosterConfig(host = maker.id, roles = listOf(maker, checker), verifier = checker.id)

        assertEquals(checker.id, roster.verifier)
        assertEquals(checker.id, roster.reviewerOf(maker.id))
        assertNull(
            roster.resolverFor(ProbeId(SequenceProbe.ID)),
            "naming a verifier names no resolver; which seat settles a Probe is still an override",
        )
    }

    @Test
    fun `a verifier who holds no seat is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RosterConfig(host = solo.id, roles = listOf(solo), verifier = RoleId("ghost"))
        }
        assertTrue(failure.message.orEmpty().contains("ghost"), failure.message)
    }

    @Test
    fun `a roster decoded with a verifier who holds no seat is refused`() {
        val encoded = """{"host":"solo","verifier":"ghost","roles":""" +
            """[{"id":"solo","title":"Solo","instructions":{"id":"s","version":1}}]}"""

        val failure = assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(RosterConfig.serializer(), encoded)
        }
        assertTrue(failure.message.orEmpty().contains("ghost"), failure.message)
    }

    @Test
    fun `a roster round-trips with a verifier and a seat's execution assignment`() {
        val maker = RoleConfig(
            id = RoleId("maker"),
            title = "Maker",
            instructions = PromptRef("consumer.maker", 2),
            tools = setOf("web_fetch"),
            execution = ExecutionAssignment(model = "the-big-one", effort = EffortLevel.HIGH),
        )
        val checker = RoleConfig(
            id = RoleId("checker"),
            title = "Checker",
            instructions = PromptRef("consumer.checker", 1),
            reviews = setOf(maker.id),
        )
        val roster = RosterConfig(host = maker.id, roles = listOf(maker, checker), verifier = checker.id)

        val encoded = json.encodeToString(RosterConfig.serializer(), roster)
        val decoded = json.decodeFromString(RosterConfig.serializer(), encoded)

        assertEquals(roster, decoded)
        assertTrue(encoded.contains("\"verifier\":\"checker\""), encoded)
        assertEquals(EffortLevel.HIGH, decoded.byId(maker.id)?.execution?.effort)
        assertEquals("the-big-one", decoded.byId(maker.id)?.execution?.model)
        assertNull(decoded.byId(checker.id)?.execution, "a seat that declares no assignment decodes to none")
    }

    @Test
    fun `a roster built from plain ids is the roster built from role ids`() {
        val fromRoleIds = RosterConfig(
            host = solo.id,
            roles = listOf(solo.copy(reviews = setOf(solo.id))),
            verifier = solo.id,
        )

        val fromStrings = RosterConfig.of(
            host = "solo",
            roles = listOf(
                RoleConfig.of(
                    id = "solo",
                    title = "Solo",
                    instructions = PromptRef("consumer.solo", 1),
                    tools = setOf("web_search"),
                    reviews = setOf("solo"),
                ),
            ),
            verifier = "solo",
        )

        assertEquals(fromRoleIds, fromStrings)
    }
}
