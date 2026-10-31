package link.socket.ampere.agents.execution.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading `ampere-scope` blocks (AMPR-306 M1) and deciding overlap (AMPR-308).
 *
 * The fixtures are the TicketConventions cell's own worked examples, so a change to
 * the convention that this reader does not follow fails here.
 */
class TicketScopesTest {

    private fun block(yaml: String): String =
        "Intro.\n\n```ampere-scope\n${yaml.trimIndent()}\n```\n\n## Task Sequence\n"

    @Test
    fun `the conventions' single-repo example parses as exclusive`() {
        val scope = TicketScopes.parse(
            block(
                """
                repos:
                  - repo: socket-link/ampere
                    paths:
                      - ampere-core/src/commonMain/kotlin/link/socket/ampere/probe/**
                      - ampere-core/src/commonTest/kotlin/link/socket/ampere/probe/**
                      - docs/concepts/probe.md
                """,
            ),
        )

        val declared = assertIs<TicketScope.Declared>(scope)
        assertTrue(declared.exclusive, "an absent `exclusive` must mean true")
        assertEquals(3, declared.entryFor("socket-link/ampere")?.paths?.size)
    }

    @Test
    fun `the conventions' multi-repo example keeps each repository's globs apart`() {
        val declared = assertIs<TicketScope.Declared>(
            TicketScopes.parse(
                block(
                    """
                    repos:
                      - repo: socket-link/ampere
                        paths:
                          - docs/concepts/lifecycle-types.md
                      - repo: socket-link/phosphor
                        paths:
                          - src/commonMain/kotlin/link/socket/phosphor/scene/SceneGate.kt
                    exclusive: true
                    """,
                ),
            ),
        )
        assertEquals(listOf("socket-link/ampere", "socket-link/phosphor"), declared.repos.map { it.repo })
    }

    @Test
    fun `a ticket with no block, two blocks or an unparseable block has unknown scope`() {
        assertIs<TicketScope.Unknown>(TicketScopes.parse(null))
        assertIs<TicketScope.Unknown>(TicketScopes.parse("Prose scope: touches the probe package."))

        val one = "repos:\n  - repo: socket-link/ampere\n    paths:\n      - docs/a.md"
        assertIs<TicketScope.Unknown>(TicketScopes.parse(block(one) + block(one)))

        // The conventions' first anti-pattern: an unquoted glob starting with `*` is a
        // YAML scanner error, which must read as unknown scope — never as "writes nothing".
        val unquoted = TicketScopes.parse(block("repos:\n  - repo: socket-link/ampere\n    paths:\n      - **/*.kt"))
        assertIs<TicketScope.Unknown>(unquoted)
    }

    @Test
    fun `a misspelt key, an empty path list or a path that climbs out is unknown scope`() {
        assertIs<TicketScope.Unknown>(
            TicketScopes.parse(
                block("repos:\n  - repo: socket-link/ampere\n    paths:\n      - docs/a.md\nexlusive: false"),
            ),
        )
        assertIs<TicketScope.Unknown>(TicketScopes.parse(block("repos:\n  - repo: socket-link/ampere\n    paths: []")))
        assertIs<TicketScope.Unknown>(
            TicketScopes.parse(block("repos:\n  - repo: socket-link/ampere\n    paths:\n      - ../elsewhere/x.kt")),
        )
        assertIs<TicketScope.Unknown>(
            TicketScopes.parse(block("repos:\n  - repo: socket-link/ampere\n    paths:\n      - /etc/passwd")),
        )
        assertIs<TicketScope.Unknown>(TicketScopes.parse(block("repos:\n  - repo: ampere\n    paths:\n      - a.md")))
    }

    @Test
    fun `globs whose literal prefixes diverge are disjoint`() {
        assertFalse(
            TicketScopes.mayOverlap(
                "ampere-core/src/commonMain/kotlin/link/socket/ampere/probe/**",
                "ampere-core/src/commonTest/kotlin/link/socket/ampere/probe/**",
            ),
        )
        assertFalse(TicketScopes.mayOverlap("docs/a.md", "docs/b.md"))
    }

    @Test
    fun `overlap fails toward yes`() {
        assertTrue(TicketScopes.mayOverlap("docs/a.md", "docs/a.md"))
        assertTrue(TicketScopes.mayOverlap("docs/**", "docs/concepts/a.md"), "a subtree covers a file in it")
        assertTrue(TicketScopes.mayOverlap("ampere-core/src", "ampere-core/src/Foo.kt"), "a bare directory covers")
        assertTrue(TicketScopes.mayOverlap("**", "anything/at/all.kt"))
        assertTrue(TicketScopes.mayOverlap("*.md", "docs/a.md"), "`*` may cross `/` under some reading")
        assertTrue(TicketScopes.mayOverlap("docs/*/a.md", "docs/x/**"))
        assertTrue(TicketScopes.mayOverlap("docs/{a,b}.md", "docs/a.md"))
    }

    @Test
    fun `two scopes overlap only within the same repository`() {
        val ampere = TicketScope.Declared(listOf(RepoScope("socket-link/ampere", listOf("src/**"))))
        val phosphor = TicketScope.Declared(listOf(RepoScope("socket-link/phosphor", listOf("src/**"))))
        assertNull(TicketScopes.overlap(ampere, phosphor))
        assertNotNull(TicketScopes.overlap(ampere, ampere))
    }

    @Test
    fun `only a mutual exclusive false lets overlapping scopes run together`() {
        val shared = listOf(RepoScope("socket-link/ampere", listOf("docs/new.md")))
        val loose = TicketScope.Declared(shared, exclusive = false)
        val strict = TicketScope.Declared(shared, exclusive = true)

        assertNull(TicketScopes.overlap(loose, loose))
        assertNotNull(TicketScopes.overlap(loose, strict), "one side asked for its paths to itself")
    }

    @Test
    fun `unknown scope overlaps everything`() {
        val unknown = TicketScope.Unknown("no block")
        val declared = TicketScope.Declared(
            listOf(RepoScope("socket-link/ampere", listOf("docs/a.md"))),
            exclusive = false,
        )
        assertNotNull(TicketScopes.overlap(unknown, declared))
        assertNotNull(TicketScopes.overlap(declared, unknown))
    }
}
