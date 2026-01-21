package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcRegistryTest {

    @Test
    fun `list returns all built-in arcs`() {
        val arcs = ArcRegistry.list()

        val names = arcs.map { it.name }.toSet()
        assertEquals(6, arcs.size)
        assertTrue(names.containsAll(
            listOf(
                "startup-saas",
                "devops-pipeline",
                "research-paper",
                "data-pipeline",
                "security-audit",
                "content-creation",
            )
        ))
    }

    @Test
    fun `get returns arc by name case-insensitively`() {
        val arc = ArcRegistry.get("Startup-SaaS")

        assertNotNull(arc)
        assertEquals("startup-saas", arc.name)
        assertEquals(listOf("pm", "code", "qa"), arc.orchestration.order)
    }

    @Test
    fun `getDefault returns startup-saas arc`() {
        val arc = ArcRegistry.getDefault()

        assertEquals("startup-saas", arc.name)
    }
}
