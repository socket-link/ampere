package link.socket.ampere.agents.execution.verify

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlin.time.Duration.Companion.minutes

/**
 * One way a manifest breaks schema v1.
 *
 * @property field Where, as a path a person can find in the file: `gates[3]
 *   (jvm-test-ampere-core).timeout_minutes`, `env.requires[1].toolchain`, or `(file)`
 *   for a document that is not YAML at all.
 */
data class ManifestViolation(val field: String, val message: String) {
    override fun toString(): String = "$field: $message"
}

/** A manifest that is not valid schema v1, with every violation found — not only the first. */
class ManifestInvalid(val violations: List<ManifestViolation>) :
    Exception("the verification manifest is not valid schema v1:\n" + violations.joinToString("\n") { "  - $it" })

/**
 * Parses `.ampere/verify.yml` into a [VerifyManifest], refusing anything schema v1
 * does not describe (AMPR-309, Expected Outcome 1).
 *
 * Written against the schema document (`docs/verify-manifest.md`), not against the
 * recon's dry-run interpreter, which was evidence and never product.
 *
 * ### Strict, and exhaustive about it
 *
 * Every rule the schema states is a violation here, and the parse reports all of
 * them at once with the field each one is about. An unknown key anywhere is a
 * violation — a misspelled `fersh: true` would otherwise be a gate silently served
 * from a cache. So is a `version` this build does not implement: the runner refuses
 * rather than reading a later schema optimistically. So is a command that bundles
 * more than one task ([GateCommand.bundling]), which is the rule schema v1 was
 * ratified for.
 *
 * Booleans are `true` or `false` and nothing else. YAML 1.1's `yes`/`on` are not
 * accepted: a gate's `fresh` is too important to depend on which YAML dialect a
 * person had in mind.
 */
object VerifyManifestParser {

    /** Parse [text], or fail with [ManifestInvalid]. */
    fun parse(text: String): Result<VerifyManifest> {
        val root = try {
            Yaml.default.parseToYamlNode(text)
        } catch (e: Exception) {
            return invalid(ManifestViolation("(file)", "not readable as YAML: ${e.message ?: e::class.simpleName}"))
        }
        val context = Context()
        val manifest = context.manifest(root)
        return if (context.violations.isEmpty() && manifest != null) {
            Result.success(manifest)
        } else {
            Result.failure(ManifestInvalid(context.violations.ifEmpty { listOf(ManifestViolation("(file)", "empty")) }))
        }
    }

    private fun invalid(violation: ManifestViolation): Result<VerifyManifest> =
        Result.failure(ManifestInvalid(listOf(violation)))

    private class Context {
        val violations = mutableListOf<ManifestViolation>()

        fun violation(field: String, message: String) {
            violations += ManifestViolation(field, message)
        }

        fun manifest(root: YamlNode): VerifyManifest? {
            val map = root.unwrap() as? YamlMap ?: run {
                violation("(file)", "the document must be a mapping with keys version, env, gates")
                return null
            }
            val entries = keysOf(map, "", TOP_LEVEL_KEYS)

            val version = entries["version"]?.let { node ->
                int(node, "version")?.also {
                    if (it != VerifyManifest.SUPPORTED_VERSION) {
                        violation(
                            "version",
                            "$it is not a schema version this runner implements " +
                                "(only ${VerifyManifest.SUPPORTED_VERSION})",
                        )
                    }
                }
            } ?: run {
                if ("version" !in entries) violation("version", "required")
                null
            }

            val (requires, optional) = entries["env"]?.let { env(it) } ?: run {
                if ("env" !in entries) violation("env", "required")
                emptyList<Precondition>() to emptyList()
            }
            val hardIds = requires.map { it.id }.toSet()
            val softIds = optional.map { it.id }.toSet()
            (hardIds intersect softIds).forEach {
                violation("env", "precondition id `$it` is declared under both requires and optional")
            }

            val gates = entries["gates"]?.let { gates(it, hardIds, softIds) } ?: run {
                if ("gates" !in entries) violation("gates", "required")
                emptyList()
            }

            return VerifyManifest(
                version = version ?: return null,
                requires = requires,
                optional = optional,
                gates = gates,
            )
        }

        fun env(node: YamlNode): Pair<List<Precondition>, List<Precondition>> {
            val map = node.unwrap() as? YamlMap ?: run {
                violation("env", "must be a mapping with keys requires, optional")
                return emptyList<Precondition>() to emptyList()
            }
            val entries = keysOf(map, "env", ENV_KEYS)
            val requires = entries["requires"]?.let { preconditions(it, "env.requires") }.orEmpty()
            val optional = entries["optional"]?.let { preconditions(it, "env.optional") }.orEmpty()
            return requires to optional
        }

        fun preconditions(node: YamlNode, field: String): List<Precondition> {
            if (node.unwrap() is YamlNull) return emptyList()
            val list = node.unwrap() as? YamlList ?: run {
                violation(field, "must be a list")
                return emptyList()
            }
            val seen = mutableSetOf<String>()
            return list.items.mapIndexedNotNull { index, item ->
                val at = "$field[$index]"
                val map = item.unwrap() as? YamlMap ?: run {
                    violation(at, "must be a mapping with an id and one predicate")
                    return@mapIndexedNotNull null
                }
                val entries = keysOf(map, at, PRECONDITION_KEYS)
                val id = entries["id"]?.let { identifier(it, "$at.id") } ?: run {
                    if ("id" !in entries) violation("$at.id", "required")
                    null
                }
                val predicates = PREDICATE_KEYS.filter { it in entries }
                if (predicates.size != 1) {
                    violation(
                        at,
                        "must have exactly one predicate of ${PREDICATE_KEYS.joinToString()}, found " +
                            if (predicates.isEmpty()) "none" else predicates.joinToString(),
                    )
                }
                val reason = entries["reason"]?.let { string(it, "$at.reason") }
                val key = predicates.firstOrNull() ?: return@mapIndexedNotNull null
                val value = entries.getValue(key).let { string(it, "$at.$key") } ?: return@mapIndexedNotNull null
                if (value.isBlank()) {
                    violation("$at.$key", "must not be blank")
                    return@mapIndexedNotNull null
                }
                val predicate = when (key) {
                    "toolchain" -> PreconditionPredicate.Toolchain(value)
                    "file" -> PreconditionPredicate.File(value)
                    "command" -> {
                        commandRule(value, "$at.command", gate = false)
                        PreconditionPredicate.Command(value)
                    }

                    else -> PreconditionPredicate.Simulator(value)
                }
                id ?: return@mapIndexedNotNull null
                if (!seen.add(id)) {
                    violation("$at.id", "`$id` is declared twice")
                    return@mapIndexedNotNull null
                }
                Precondition(id = id, predicate = predicate, reason = reason)
            }
        }

        fun gates(node: YamlNode, hard: Set<String>, soft: Set<String>): List<ManifestGate> {
            val list = node.unwrap() as? YamlList ?: run {
                violation("gates", "must be a list")
                return emptyList()
            }
            if (list.items.isEmpty()) {
                violation("gates", "must declare at least one gate; a definition of done with none is not one")
            }
            val seen = mutableSetOf<String>()
            return list.items.mapIndexedNotNull { index, item -> gate(item, index, hard, soft, seen) }
        }

        fun gate(
            item: YamlNode,
            index: Int,
            hard: Set<String>,
            soft: Set<String>,
            seen: MutableSet<String>,
        ): ManifestGate? {
            val map = item.unwrap() as? YamlMap ?: run {
                violation("gates[$index]", "must be a mapping")
                return null
            }
            val idNode = map.entries.entries.firstOrNull { it.key.content == "id" }?.value?.unwrap()
            val rawId = (idNode as? YamlScalar)?.content
            val at = if (rawId.isNullOrBlank()) "gates[$index]" else "gates[$index] ($rawId)"
            val entries = keysOf(map, at, GATE_KEYS)
            val before = violations.size

            val id = entries["id"]?.let { identifier(it, "$at.id") } ?: run {
                if ("id" !in entries) violation("$at.id", "required")
                null
            }
            if (id != null && !seen.add(id)) {
                violation("$at.id", "`$id` is not unique; a gate id is the attribution key")
            }

            val hasRun = "run" in entries
            val hasSteps = "steps" in entries
            if (hasRun == hasSteps) violation(at, "must have exactly one of run, steps")
            val steps = when {
                hasRun && !hasSteps -> entries.getValue("run").let { string(it, "$at.run") }?.let { run ->
                    commandRule(run, "$at.run", gate = true)
                    listOf(GateStep(id = null, run = run))
                }

                hasSteps && !hasRun -> steps(entries.getValue("steps"), "$at.steps")
                else -> null
            }

            val fresh = entries["fresh"]?.let { boolean(it, "$at.fresh") } ?: false
            val timeout = required(entries, at, "timeout_minutes") { int(it, "$at.timeout_minutes") }?.also {
                if (it <= 0) violation("$at.timeout_minutes", "must be a positive number of minutes")
            }
            val retrySafe = required(entries, at, "retry_safe") { boolean(it, "$at.retry_safe") }
            val sideEffects = required(entries, at, "side_effects") { node ->
                string(node, "$at.side_effects")?.let { value ->
                    SideEffects.fromWire(value) ?: run {
                        val allowed = SideEffects.entries.joinToString { it.wire }
                        violation("$at.side_effects", "`$value` is not one of $allowed")
                        null
                    }
                }
            }
            val parallelSafe = required(entries, at, "parallel_safe") { boolean(it, "$at.parallel_safe") }
            val baseline = required(entries, at, "baseline") { node ->
                string(node, "$at.baseline")?.let { value ->
                    Baseline.fromWire(value) ?: run {
                        violation("$at.baseline", "`$value` is not one of ${Baseline.entries.joinToString { it.wire }}")
                        null
                    }
                }
            }
            val requires = entries["requires"]
                ?.let { references(it, "$at.requires", hard, soft, "env.requires") }
                .orEmpty()
            val needs = entries["needs"]?.let { references(it, "$at.needs", soft, hard, "env.optional") }.orEmpty()

            if (violations.size != before) return null
            return ManifestGate(
                id = id ?: return null,
                steps = steps ?: return null,
                sequenced = hasSteps,
                fresh = fresh,
                timeout = (timeout ?: return null).minutes,
                retrySafe = retrySafe ?: return null,
                sideEffects = sideEffects ?: return null,
                parallelSafe = parallelSafe ?: return null,
                requires = requires,
                needs = needs,
                baseline = baseline ?: return null,
            )
        }

        fun steps(node: YamlNode, field: String): List<GateStep>? {
            val list = node.unwrap() as? YamlList ?: run {
                violation(field, "must be a list of {id, run}")
                return null
            }
            if (list.items.size < 2) {
                violation(field, "a sequence needs at least two steps; a single command is `run`")
            }
            val seen = mutableSetOf<String>()
            val steps = list.items.mapIndexedNotNull { index, item ->
                val at = "$field[$index]"
                val map = item.unwrap() as? YamlMap ?: run {
                    violation(at, "must be a mapping with id and run")
                    return@mapIndexedNotNull null
                }
                val entries = keysOf(map, at, STEP_KEYS)
                val id = entries["id"]?.let { identifier(it, "$at.id") } ?: run {
                    if ("id" !in entries) violation("$at.id", "required; a step's id is what a failure names")
                    null
                }
                if (id != null && !seen.add(id)) violation("$at.id", "`$id` is not unique within the gate")
                val run = entries["run"]?.let { string(it, "$at.run") } ?: run {
                    if ("run" !in entries) violation("$at.run", "required")
                    null
                }
                run?.let { commandRule(it, "$at.run", gate = true) }
                if (id == null || run == null) null else GateStep(id = id, run = run)
            }
            return steps.takeIf { it.size == list.items.size }
        }

        fun references(
            node: YamlNode,
            field: String,
            allowed: Set<String>,
            other: Set<String>,
            declaredIn: String,
        ): List<String> {
            val list = node.unwrap() as? YamlList ?: run {
                violation(field, "must be a list of precondition ids")
                return emptyList()
            }
            return list.items.mapIndexedNotNull { index, item ->
                val id = string(item, "$field[$index]") ?: return@mapIndexedNotNull null
                when (id) {
                    in allowed -> id
                    in other -> {
                        violation("$field[$index]", "`$id` is declared, but not under $declaredIn")
                        null
                    }

                    else -> {
                        violation(
                            "$field[$index]",
                            "`$id` is not a declared precondition; an undeclared reference is an error, not a skip",
                        )
                        null
                    }
                }
            }.also { ids ->
                ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    violation(field, "`$it` is listed twice")
                }
            }
        }

        /** The rules every command obeys: one command, no masking, and — for a gate — one task. */
        fun commandRule(run: String, field: String, gate: Boolean) {
            val argv = try {
                GateCommand.argv(run)
            } catch (e: GateCommand.Malformed) {
                violation(field, "contains ${e.message}")
                return
            }
            if (gate) GateCommand.bundling(argv)?.let { violation(field, "bundles work: $it") }
        }

        fun <T> required(entries: Map<String, YamlNode>, at: String, key: String, read: (YamlNode) -> T?): T? {
            val node = entries[key] ?: run {
                violation("$at.$key", "required")
                return null
            }
            return read(node)
        }

        /** The entries of [map] by key, after reporting every key not in [allowed]. */
        fun keysOf(map: YamlMap, at: String, allowed: List<String>): Map<String, YamlNode> {
            val entries = linkedMapOf<String, YamlNode>()
            map.entries.forEach { (key, value) ->
                val name = key.content
                if (name !in allowed) {
                    val where = if (at.isEmpty()) name else "$at.$name"
                    violation(where, "unknown field; schema v1 allows ${allowed.joinToString()}")
                } else {
                    entries[name] = value
                }
            }
            return entries
        }

        fun string(node: YamlNode, field: String): String? = when (val value = node.unwrap()) {
            is YamlScalar -> value.content
            else -> {
                violation(field, "must be a string")
                null
            }
        }

        fun identifier(node: YamlNode, field: String): String? = string(node, field)?.let { id ->
            if (IDENTIFIER.matches(id)) {
                id
            } else {
                violation(field, "`$id` is not a literal id (letters, digits, '.', '_', '-')")
                null
            }
        }

        fun int(node: YamlNode, field: String): Int? = string(node, field)?.let { text ->
            text.toIntOrNull() ?: run {
                violation(field, "`$text` is not a whole number")
                null
            }
        }

        fun boolean(node: YamlNode, field: String): Boolean? = string(node, field)?.let { text ->
            when (text) {
                "true" -> true
                "false" -> false
                else -> {
                    violation(field, "`$text` is not true or false")
                    null
                }
            }
        }

        private fun YamlNode.unwrap(): YamlNode = if (this is YamlTaggedNode) innerNode.unwrap() else this
    }

    private val TOP_LEVEL_KEYS = listOf("version", "env", "gates")
    private val ENV_KEYS = listOf("requires", "optional")
    private val PREDICATE_KEYS = listOf("toolchain", "file", "command", "simulator")
    private val PRECONDITION_KEYS = listOf("id") + PREDICATE_KEYS + "reason"
    private val GATE_KEYS = listOf(
        "id",
        "run",
        "steps",
        "fresh",
        "timeout_minutes",
        "retry_safe",
        "side_effects",
        "parallel_safe",
        "requires",
        "needs",
        "baseline",
    )
    private val STEP_KEYS = listOf("id", "run")

    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}
