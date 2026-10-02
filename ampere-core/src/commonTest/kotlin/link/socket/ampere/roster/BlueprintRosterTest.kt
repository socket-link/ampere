package link.socket.ampere.roster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.safety.SafetyProbe

/** AMPR-379 task 1 validation: six roles, acyclic reviews, and the resolver rule. */
class BlueprintRosterTest {

    @Test
    fun `all returns six roles with distinct ids`() {
        val roles = BlueprintRoster.all()

        assertEquals(6, roles.size)
        assertEquals(6, roles.map { it.id }.toSet().size)
        assertEquals(
            listOf("planner", "estimator", "scout", "scheduler", "inspector", "coordinator"),
            roles.map { it.id.value },
        )
    }

    @Test
    fun `reviews are acyclic and only the inspector reviews`() {
        val roles = BlueprintRoster.all()

        assertTrue(roles.reviewsAreAcyclic())
        assertEquals(setOf(BlueprintRoster.planner.id, BlueprintRoster.scout.id), BlueprintRoster.inspector.reviews)
        roles.filter { it.id != BlueprintRoster.inspector.id }.forEach { role ->
            assertTrue(role.reviews.isEmpty(), "${role.id.value} should review nobody")
        }
    }

    @Test
    fun `reviewerOf names the inspector for planner and scout and nobody otherwise`() {
        assertEquals(BlueprintRoster.inspector.id, BlueprintRoster.reviewerOf(BlueprintRoster.planner.id))
        assertEquals(BlueprintRoster.inspector.id, BlueprintRoster.reviewerOf(BlueprintRoster.scout.id))
        assertNull(BlueprintRoster.reviewerOf(BlueprintRoster.inspector.id))
        assertNull(BlueprintRoster.reviewerOf(BlueprintRoster.coordinator.id))
    }

    @Test
    fun `a review cycle is detected`() {
        val a = RoleConfig(RoleId("a"), "A", PromptRef("a", 1), reviews = setOf(RoleId("b")))
        val b = RoleConfig(RoleId("b"), "B", PromptRef("b", 1), reviews = setOf(RoleId("c")))
        val c = RoleConfig(RoleId("c"), "C", PromptRef("c", 1), reviews = setOf(RoleId("a")))

        assertFalse(listOf(a, b, c).reviewsAreAcyclic())
        assertTrue(listOf(a, b).reviewsAreAcyclic(), "an edge to a role not on the list is not a cycle")
    }

    @Test
    fun `the host is the coordinator and the verifier is the inspector`() {
        assertEquals(BlueprintRoster.coordinator.id, BlueprintRoster.host)
        assertEquals(BlueprintRoster.inspector.id, BlueprintRoster.verifier)
    }

    @Test
    fun `a sequence or safety verdict resolves to the planner and any other to the scout`() {
        assertEquals(BlueprintRoster.planner.id, BlueprintRoster.resolverFor(SequenceProbe().id))
        assertEquals(BlueprintRoster.planner.id, BlueprintRoster.resolverFor(ProbeId(SequenceProbe.ID)))
        assertEquals(BlueprintRoster.planner.id, BlueprintRoster.resolverFor(ProbeId(SafetyProbe.ID)))
        assertEquals(BlueprintRoster.scout.id, BlueprintRoster.resolverFor(ProbeId("ampere.freshness")))
        assertEquals(BlueprintRoster.scout.id, BlueprintRoster.resolverFor(ProbeId("blueprint.part-fit")))
    }

    @Test
    fun `every role runs under a versioned prompt this build carries`() {
        BlueprintRoster.all().forEach { role ->
            val text = RosterPrompts.text(role.instructions)
            assertNotNull(text, "${role.id.value} prompt ${role.instructions.key} is missing")
            assertTrue(text.isNotBlank())
            assertEquals(1, role.instructions.version)
        }
        assertEquals(6, BlueprintRoster.all().map { it.instructions }.toSet().size)
        assertNull(RosterPrompts.text(PromptRef("blueprint.planner", 99)))
    }

    @Test
    fun `the blueprint vocabulary lives in the prompts and not in the types`() {
        val scout = RosterPrompts.text(BlueprintRoster.scout.instructions).orEmpty()
        assertTrue(scout.contains("lead times"))
        assertTrue(scout.contains("CFM"))
        assertEquals("blueprint.scout@v1", BlueprintRoster.scout.instructions.key)
    }

    @Test
    fun `byId finds a role and returns null for a stranger`() {
        assertEquals(BlueprintRoster.scout, BlueprintRoster.byId(RoleId("scout")))
        assertNull(BlueprintRoster.byId(RoleId("intern")))
    }
}
