package link.socket.ampere.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KnowledgeScopeTest {

    @Test
    fun `predefined scopes have stable canonical names`() {
        assertEquals("personal", KnowledgeScope.Personal.name)
        assertEquals("work", KnowledgeScope.Work.name)
        assertEquals("reading", KnowledgeScope.Reading.name)
    }

    @Test
    fun `scopes with the same name are equal`() {
        assertEquals(KnowledgeScope("work"), KnowledgeScope.Work)
        assertEquals(KnowledgeScope("custom-scope"), KnowledgeScope("custom-scope"))
    }

    @Test
    fun `scopes with different names are not equal`() {
        assertNotEquals(KnowledgeScope.Work, KnowledgeScope.Personal)
    }

    @Test
    fun `blank scope name is rejected`() {
        assertFailsWith<IllegalArgumentException> { KnowledgeScope("") }
        assertFailsWith<IllegalArgumentException> { KnowledgeScope("   ") }
    }

    @Test
    fun `scope name with surrounding whitespace is rejected`() {
        assertFailsWith<IllegalArgumentException> { KnowledgeScope(" work") }
        assertFailsWith<IllegalArgumentException> { KnowledgeScope("work ") }
    }
}
