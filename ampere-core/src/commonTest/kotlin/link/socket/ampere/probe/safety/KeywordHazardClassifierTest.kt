package link.socket.ampere.probe.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.safety.SafetyFixtures.line
import link.socket.ampere.probe.safety.SafetyFixtures.task

/** AMPR-380 task 1 validation: the deterministic rules, and what they refuse to match. */
class KeywordHazardClassifierTest {

    private val classifier = KeywordHazardClassifier

    private suspend fun classify(
        item: CanonWorkItem,
        lines: List<LineRef> = emptyList(),
    ): List<HazardFinding> = classifier.classify(item, lines).getOrThrow()

    @Test
    fun `wiring the fan to mains is electrical and disconnects power first`() = runTest {
        val findings = classify(task("mount-fan", title = "Wire the fan to mains"))

        assertEquals(
            listOf(
                HazardFinding(
                    category = HazardCategory.ELECTRICAL,
                    subject = HazardSubject.Task(CanonId("mount-fan")),
                    evidence = "task text matched \"wire\"",
                    mitigationHint = MitigationHint.DISCONNECT_POWER_FIRST,
                ),
            ),
            findings,
        )
    }

    @Test
    fun `a manifest line of kind SOLVENT is fumes and confirms ventilation`() = runTest {
        val findings = classify(
            task("seal-duct", title = "Seal the duct joints"),
            listOf(line("line-sealant", kind = "SOLVENT", label = "Duct sealant", appliesTo = setOf("seal-duct"))),
        )

        assertEquals(
            listOf(
                HazardFinding(
                    category = HazardCategory.FUMES_OR_CHEMICALS,
                    subject = HazardSubject.Task(CanonId("seal-duct")),
                    evidence = "manifest line line-sealant has kind SOLVENT",
                    mitigationHint = MitigationHint.CONFIRM_VENTILATION,
                ),
            ),
            findings,
        )
    }

    @Test
    fun `a mains line outranks the keyword rule and asks for the code check`() = runTest {
        val findings = classify(
            task("mount-fan", title = "Wire the fan to mains"),
            listOf(line("line-fan", kind = "mains-voltage", label = "Inline fan", appliesTo = setOf("mount-fan"))),
        )

        assertEquals(1, findings.size, "one finding per category per task: $findings")
        assertEquals(MitigationHint.CHECK_LOCAL_CODE, findings.single().mitigationHint)
        assertEquals("manifest line line-fan has kind MAINS_VOLTAGE", findings.single().evidence)
    }

    @Test
    fun `line kinds normalize across casing and separators`() = runTest {
        listOf("MAINS_VOLTAGE", "mains voltage", "mains-voltage", "Mains  Voltage").forEach { kind ->
            val findings = classify(
                task("t", title = "A step"),
                listOf(line("l", kind = kind, label = "part", appliesTo = setOf("t"))),
            )
            assertEquals(HazardCategory.ELECTRICAL, findings.single().category, "kind $kind was not recognized")
        }
        assertTrue("MAINS_VOLTAGE" in KeywordHazardClassifier.recognizedLineKinds)
        assertTrue("SOLVENT" in KeywordHazardClassifier.recognizedLineKinds)
    }

    @Test
    fun `an unrecognized kind falls back to the line label`() = runTest {
        val findings = classify(
            task("pour", title = "Pour the mix"),
            listOf(line("l", kind = "PART", label = "Two-part epoxy resin", appliesTo = setOf("pour"))),
        )

        assertEquals(HazardCategory.FUMES_OR_CHEMICALS, findings.single().category)
        assertEquals(MitigationHint.FOLLOW_MANUFACTURER_SDS, findings.single().mitigationHint)
        assertEquals("manifest line l matched \"resin\"", findings.single().evidence)
    }

    @Test
    fun `a matching word inside a longer word is not a match`() = runTest {
        val findings = classify(
            task("admin", title = "Execute the handover and record what remains outstanding"),
        )

        assertEquals(emptyList(), findings, "substring matching would read cut from Execute and mains from remains")
    }

    @Test
    fun `at most one finding per category per task`() = runTest {
        val findings = classify(task("cut", title = "Cut and saw the duct then drill the flange"))

        assertEquals(1, findings.size)
        assertEquals(HazardCategory.CUTTING_OR_POWER_TOOLS, findings.single().category)
        assertEquals("task text matched \"cut\"", findings.single().evidence)
    }

    @Test
    fun `two different categories on one task are two findings`() = runTest {
        val findings = classify(task("both", title = "Cut the opening and wire the fan"))

        assertEquals(
            listOf(HazardCategory.ELECTRICAL, HazardCategory.CUTTING_OR_POWER_TOOLS),
            findings.map { it.category },
            "rule order decides the order of findings",
        )
    }

    @Test
    fun `a phrase rule matches across words`() = runTest {
        val findings = classify(task("header", title = "Cut into the load-bearing header"))

        assertTrue(findings.any { it.category == HazardCategory.STRUCTURAL_OR_LOAD })
        assertEquals(
            MitigationHint.TWO_PERSON_LIFT,
            findings.first { it.category == HazardCategory.STRUCTURAL_OR_LOAD }.mitigationHint,
        )
    }

    @Test
    fun `description prose and labels are read as well as the title`() = runTest {
        val fromNotes = classify(task("a", title = "Step one", notes = "Bring the ladder up first"))
        val fromLabels = classify(task("b", title = "Step two", labels = listOf("needs-propane")))

        assertEquals(HazardCategory.WORKING_AT_HEIGHT, fromNotes.single().category)
        assertEquals(HazardCategory.PRESSURE_OR_GAS, fromLabels.single().category)
    }

    @Test
    fun `a task with nothing to read is a typed failure rather than a clean pass`() = runTest {
        val failure = classifier.classify(task("blank", title = "   "), emptyList()).exceptionOrNull()

        assertIs<UnclassifiableSubject>(failure)
        assertEquals(HazardSubject.Task(CanonId("blank")), failure.subject)
        assertEquals(UndeterminedCause.EVIDENCE_ABSENT, failure.undeterminedCause)
    }

    @Test
    fun `a blank title is still readable when a manifest line is attached`() = runTest {
        val findings = classify(
            task("blank", title = ""),
            listOf(line("l", kind = "SOLVENT", label = "Solvent", appliesTo = setOf("blank"))),
        )

        assertEquals(HazardCategory.FUMES_OR_CHEMICALS, findings.single().category)
    }

    @Test
    fun `an unattached line is reported against itself`() = runTest {
        val findings = classifier.classifyUnattachedLines(
            listOf(
                line("line-solvent", kind = "SOLVENT", label = "Acetone"),
                line("line-screws", kind = "FASTENER", label = "Stainless screws"),
            ),
        ).getOrThrow()

        assertEquals(
            listOf(
                HazardFinding(
                    category = HazardCategory.FUMES_OR_CHEMICALS,
                    subject = HazardSubject.Line("line-solvent"),
                    evidence = "manifest line line-solvent has kind SOLVENT",
                    mitigationHint = MitigationHint.CONFIRM_VENTILATION,
                ),
            ),
            findings,
        )
    }

    @Test
    fun `every mitigation hint is reachable from some rule`() = runTest {
        val reached = listOf(
            task("a", title = "Wire the outlet"),
            task("b", title = "Mix the epoxy"),
            task("c", title = "Cut the duct"),
            task("d", title = "Solder the header pins"),
            task("e", title = "Lift the beam"),
            task("f", title = "Set up the ladder"),
            task("g", title = "Connect the propane cylinder"),
        ).flatMap { classify(it) }.map { it.mitigationHint }.toSet() +
            classify(
                task("h", title = "Connect the supply"),
                listOf(line("l", kind = "MAINS_VOLTAGE", label = "supply", appliesTo = setOf("h"))),
            ).map { it.mitigationHint } +
            classify(
                task("i", title = "Seal it"),
                listOf(line("l", kind = "SOLVENT", label = "sealant", appliesTo = setOf("i"))),
            ).map { it.mitigationHint }

        assertEquals(MitigationHint.entries.toSet(), reached, "a hint no rule produces is vocabulary nobody sees")
    }
}
