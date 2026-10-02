package link.socket.ampere.probe.safety

import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.probe.UndeterminedCause

/**
 * The v1 [HazardClassifier]: deterministic keyword and interface-kind rules over
 * what a plan already says (AMPR-380). No inference, no network, 0W.
 *
 * Three passes per Task, strongest evidence first, and **at most one finding per
 * (subject, category)** — a Task either carries a category or it does not, and
 * the first rule to fire names the mitigation:
 *
 * 1. **Manifest line kinds** ([LineRef.kind]). The manifest declaring a thing is
 *    mains-powered is stronger than a title hinting at it, and it is what
 *    distinguishes a code-regulated connection ([MitigationHint.CHECK_LOCAL_CODE])
 *    from bench wiring ([MitigationHint.DISCONNECT_POWER_FIRST]). Rules are walked
 *    in severity order, not line order, so two lines on one Task cannot swap the
 *    hint by changing places in the list.
 * 2. **Manifest line labels.** A line whose kind Ampere does not recognize can
 *    still be caught by what it is called, which is what keeps the open kind
 *    vocabulary from becoming a silent hole.
 * 3. **Task text** — title, description prose, and labels.
 *
 * Matching is whole-token (plus explicit multi-word phrases), never substring:
 * `substring` would read "execute" as a cutting hazard and "remains" as mains
 * voltage. Inflections are listed rather than stemmed, so the vocabulary is
 * auditable by reading it.
 *
 * Over-flagging is the intended failure direction. A hazard is
 * `link.socket.ampere.probe.Verdict.Warn` — the plan is not wrong, it needs a
 * mitigation Task — so a false positive costs one extra step in the plan, while
 * a false negative costs the thing this Probe exists to prevent.
 */
object KeywordHazardClassifier : HazardClassifier {

    /** A rule over a [LineRef.kind] token. */
    private data class KindRule(
        val category: HazardCategory,
        val hint: MitigationHint,
        val kinds: Set<String>,
    )

    /** A rule over words: whole tokens, or phrases matched against the normalized text. */
    private data class TextRule(
        val category: HazardCategory,
        val hint: MitigationHint,
        val tokens: Set<String> = emptySet(),
        val phrases: Set<String> = emptySet(),
    )

    /** Lowercased tokens in order, plus the space-padded normalized text phrases match against. */
    private class Corpus(val tokens: List<String>) {
        private val lookup: Set<String> = tokens.toSet()
        private val normalized: String = tokens.joinToString(separator = " ", prefix = " ", postfix = " ")

        val isEmpty: Boolean get() = tokens.isEmpty()

        /** The first token or phrase of [rule] present here, for the finding's evidence. */
        fun match(rule: TextRule): String? =
            rule.tokens.firstOrNull { it in lookup } ?: rule.phrases.firstOrNull { normalized.contains(" $it ") }
    }

    /**
     * Severity order, not line order: the first rule whose kind any line carries
     * claims the category.
     */
    private val kindRules: List<KindRule> = listOf(
        KindRule(
            HazardCategory.ELECTRICAL,
            MitigationHint.CHECK_LOCAL_CODE,
            setOf("MAINS_VOLTAGE", "MAINS", "LINE_VOLTAGE", "HIGH_VOLTAGE", "ELECTRICAL_SUPPLY"),
        ),
        KindRule(
            HazardCategory.PRESSURE_OR_GAS,
            MitigationHint.CHECK_LOCAL_CODE,
            setOf("GAS_LINE", "GAS_SUPPLY", "COMPRESSED_GAS", "PRESSURE_VESSEL", "PROPANE"),
        ),
        KindRule(
            HazardCategory.FUMES_OR_CHEMICALS,
            MitigationHint.CONFIRM_VENTILATION,
            setOf("SOLVENT", "RESIN", "EPOXY", "ADHESIVE", "PAINT", "FUEL", "CHEMICAL"),
        ),
        KindRule(
            HazardCategory.HEAT_OR_FIRE,
            MitigationHint.USE_PPE,
            setOf("HOT_SURFACE", "FLAME", "TORCH", "SOLDERING", "HEATING_ELEMENT"),
        ),
        KindRule(
            HazardCategory.CUTTING_OR_POWER_TOOLS,
            MitigationHint.USE_PPE,
            setOf("POWER_TOOL", "BLADE", "CUTTING_TOOL", "SHARP_EDGE"),
        ),
        KindRule(
            HazardCategory.STRUCTURAL_OR_LOAD,
            MitigationHint.TWO_PERSON_LIFT,
            setOf("LOAD_BEARING", "STRUCTURAL", "HEAVY"),
        ),
        KindRule(
            HazardCategory.WORKING_AT_HEIGHT,
            MitigationHint.SECURE_LADDER,
            setOf("LADDER", "SCAFFOLD", "ROOF_MOUNT"),
        ),
        KindRule(
            HazardCategory.ELECTRICAL,
            MitigationHint.DISCONNECT_POWER_FIRST,
            setOf("LOW_VOLTAGE", "DC_POWER", "BATTERY"),
        ),
    )

    private val textRules: List<TextRule> = listOf(
        TextRule(
            HazardCategory.ELECTRICAL,
            MitigationHint.DISCONNECT_POWER_FIRST,
            tokens = setOf(
                "wire", "wires", "wired", "wiring", "rewire", "rewiring", "mains", "circuit", "circuits",
                "breaker", "breakers", "outlet", "outlets", "receptacle", "receptacles", "electrical",
                "voltage", "volts", "conduit",
            ),
            phrases = setOf("junction box", "distribution board", "consumer unit"),
        ),
        TextRule(
            HazardCategory.FUMES_OR_CHEMICALS,
            MitigationHint.FOLLOW_MANUFACTURER_SDS,
            tokens = setOf(
                "resin", "epoxy", "solvent", "solvents", "adhesive", "adhesives", "glue", "glues", "paint",
                "painting", "primer", "lacquer", "varnish", "acetone", "degreaser", "thinner", "fume", "fumes",
            ),
        ),
        TextRule(
            HazardCategory.CUTTING_OR_POWER_TOOLS,
            MitigationHint.USE_PPE,
            tokens = setOf(
                "cut", "cuts", "cutting", "saw", "saws", "sawing", "blade", "blades", "drill", "drills",
                "drilling", "grinder", "grinding", "sander", "sanding", "router", "chainsaw", "jigsaw",
                "mow", "mower", "mowing", "till", "tiller", "tilling", "aerate", "aerator", "aerating",
                "trimmer", "shears", "snips",
            ),
            phrases = setOf("angle grinder", "circular saw", "power tool", "power tools"),
        ),
        TextRule(
            HazardCategory.HEAT_OR_FIRE,
            MitigationHint.USE_PPE,
            tokens = setOf(
                "solder", "soldering", "weld", "welding", "torch", "blowtorch", "braze", "brazing",
                "kiln", "flame",
            ),
            phrases = setOf("heat gun", "hot glue", "hot work"),
        ),
        TextRule(
            HazardCategory.STRUCTURAL_OR_LOAD,
            MitigationHint.TWO_PERSON_LIFT,
            tokens = setOf(
                "joist", "joists", "stud", "studs", "rafter", "rafters", "beam", "beams", "lintel",
                "footing", "footings", "truss", "trusses",
            ),
            phrases = setOf("load bearing", "heavy lift", "two person lift"),
        ),
        TextRule(
            HazardCategory.WORKING_AT_HEIGHT,
            MitigationHint.SECURE_LADDER,
            tokens = setOf(
                "ladder", "ladders", "roof", "roofs", "roofing", "scaffold", "scaffolding",
                "gutter", "gutters", "eaves",
            ),
            phrases = setOf("step stool", "at height"),
        ),
        TextRule(
            HazardCategory.PRESSURE_OR_GAS,
            MitigationHint.CHECK_LOCAL_CODE,
            tokens = setOf(
                "propane",
                "butane",
                "pressurize",
                "pressurized",
                "cylinder",
                "cylinders",
                "regulator",
                "pneumatic",
            ),
            phrases = setOf("gas line", "gas supply", "compressed air", "air compressor", "pressure vessel"),
        ),
    )

    /**
     * The [LineRef.kind] tokens this classifier recognizes, normalized.
     *
     * Public so a consumer can pin its manifest's kind vocabulary against it in a
     * test: an unrecognized kind is not an error here, which makes the drift
     * invisible unless somebody asserts on it.
     */
    val recognizedLineKinds: Set<String> = kindRules.flatMapTo(linkedSetOf()) { it.kinds }

    override suspend fun classify(task: CanonWorkItem, lines: List<LineRef>): Result<List<HazardFinding>> {
        val subject = HazardSubject.Task(task.canonId)
        val taskText = Corpus(
            tokensOf(task.title) + tokensOf(task.description?.text.orEmpty()) + task.labels.flatMap(::tokensOf),
        )
        val lineText = lines.map { line -> line to Corpus(tokensOf(line.label)) }

        if (taskText.isEmpty && lines.isEmpty()) {
            return unclassifiableTask(
                taskId = task.canonId,
                cause = UndeterminedCause.EVIDENCE_ABSENT,
                message = "task ${task.canonId.value} has no title, notes, labels or manifest lines to read",
            )
        }

        val found = linkedMapOf<HazardCategory, HazardFinding>()

        kindRules.forEach { rule ->
            val line = lines.firstOrNull { normalizeKind(it.kind) in rule.kinds } ?: return@forEach
            found.getOrPut(rule.category) {
                HazardFinding(
                    category = rule.category,
                    subject = subject,
                    evidence = kindEvidence(line),
                    mitigationHint = rule.hint,
                )
            }
        }

        lineText.forEach { (line, corpus) ->
            textRules.forEach { rule ->
                val matched = corpus.match(rule)
                if (matched != null) {
                    found.getOrPut(rule.category) {
                        HazardFinding(rule.category, subject, labelEvidence(line, matched), rule.hint)
                    }
                }
            }
        }

        textRules.forEach { rule ->
            val matched = taskText.match(rule)
            if (matched != null) {
                found.getOrPut(rule.category) {
                    HazardFinding(rule.category, subject, taskEvidence(matched), rule.hint)
                }
            }
        }

        return Result.success(found.values.toList())
    }

    override suspend fun classifyUnattachedLines(lines: List<LineRef>): Result<List<HazardFinding>> {
        val findings = lines.flatMap { line ->
            val subject = HazardSubject.Line(line.lineId)
            val corpus = Corpus(tokensOf(line.label))
            val found = linkedMapOf<HazardCategory, HazardFinding>()

            kindRules.forEach { rule ->
                if (normalizeKind(line.kind) in rule.kinds) {
                    found.getOrPut(rule.category) {
                        HazardFinding(rule.category, subject, kindEvidence(line), rule.hint)
                    }
                }
            }
            textRules.forEach { rule ->
                val matched = corpus.match(rule)
                if (matched != null) {
                    found.getOrPut(rule.category) {
                        HazardFinding(rule.category, subject, labelEvidence(line, matched), rule.hint)
                    }
                }
            }
            found.values
        }
        return Result.success(findings)
    }

    private fun kindEvidence(line: LineRef): String =
        "manifest line ${line.lineId} has kind ${normalizeKind(line.kind)}"

    private fun labelEvidence(line: LineRef, matched: String): String =
        "manifest line ${line.lineId} matched \"$matched\""

    private fun taskEvidence(matched: String): String = "task text matched \"$matched\""

    /**
     * A kind token in one shape: uppercase, every run of non-alphanumerics a single
     * `_`, no leading or trailing `_`. So `mains voltage`, `mains-voltage` and
     * `MAINS_VOLTAGE` are one kind, and a consumer's casing choice is not a
     * contract.
     */
    internal fun normalizeKind(kind: String): String =
        tokensOf(kind).joinToString("_").uppercase()

    /** Whole lowercase alphanumeric tokens, in order. No regex: this runs on every target. */
    private fun tokensOf(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        text.lowercase().forEach { character ->
            if (character.isLetterOrDigit()) {
                current.append(character)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
