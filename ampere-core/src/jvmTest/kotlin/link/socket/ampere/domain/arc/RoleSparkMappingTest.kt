package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleSparkMappingTest {

    @Test
    fun `default spark mapping returns expected spark ids`() {
        val cases = mapOf(
            "pm" to "product-management",
            "code" to "software-engineer",
            "qa" to "quality-assurance",
            "planner" to "infrastructure-planner",
            "executor" to "infrastructure-executor",
            "monitor" to "infrastructure-monitor",
            "scholar" to "research-scholar",
            "writer" to "technical-writer",
            "critic" to "content-critic",
            "analyst" to "data-analyst",
            "engineer" to "data-engineer",
            "validator" to "data-validator",
            "scanner" to "security-scanner",
            "remediator" to "security-remediator",
            "researcher" to "content-researcher",
            "editor" to "content-editor",
        )

        cases.forEach { (role, expected) ->
            assertEquals(expected, RoleSparkMapping.getDefaultSpark(role))
            assertEquals(expected, RoleSparkMapping.getDefaultSpark(role.uppercase()))
        }
    }

    @Test
    fun `unknown roles return null default spark`() {
        assertNull(RoleSparkMapping.getDefaultSpark("unknown"))
    }

    @Test
    fun `getAllSparks prepends default spark when available`() {
        val sparks = RoleSparkMapping.getAllSparks("code", listOf("rust-expert", "testing"))

        assertEquals(listOf("software-engineer", "rust-expert", "testing"), sparks)
    }

    @Test
    fun `getAllSparks returns additional sparks when role is unknown`() {
        val sparks = RoleSparkMapping.getAllSparks("custom", listOf("spark-a"))

        assertEquals(listOf("spark-a"), sparks)
    }
}
