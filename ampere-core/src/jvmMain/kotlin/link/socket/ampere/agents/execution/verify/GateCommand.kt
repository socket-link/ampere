package link.socket.ampere.agents.execution.verify

/**
 * A gate's `run` string as an argument vector, and the rules schema v1 puts on it.
 *
 * ### Why not a shell
 *
 * A gate's command runs as an argument vector, never through `sh -c`. Schema v1
 * forbids anything that could mask an exit code — `|| true`, an un-`pipefail`ed
 * `| tee`, a trailing `; echo done` — and one command per gate; with no shell there
 * is nothing that could do either, and the parser refuses the operators outright so
 * a manifest that tries reads as a schema violation rather than as a command with a
 * literal `|` argument. `${env.VAR}` substitution happens per argument after
 * splitting, so a value can never become a second argument or an operator.
 *
 * The quoting understood is the POSIX subset the committed manifests use: whitespace
 * separates, single quotes are literal, double quotes group and honour `\"`, `\\` and
 * `\$`, and a backslash outside quotes escapes the next character.
 */
object GateCommand {

    /**
     * One argument.
     *
     * @property bare Its unquoted characters only — where a shell operator would be
     *   live. A quoted `|` is a literal argument, an unquoted one is a pipeline.
     */
    data class Token(val text: String, val bare: String)

    /** A `run` string that is not one command, or not one this runner can split. */
    class Malformed(message: String) : Exception(message)

    /**
     * Split [run] into arguments.
     *
     * @throws Malformed on an unclosed quote, an empty command, a shell operator, or an
     *   interpolation other than `${env.VAR}`.
     */
    fun tokenize(run: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val text = StringBuilder()
        // The unquoted characters of the current token, where an operator would be live.
        val bare = StringBuilder()
        var inToken = false
        var i = 0
        while (i < run.length) {
            val c = run[i]
            when {
                c.isWhitespace() -> {
                    if (inToken) tokens += Token(text.toString(), bare.toString())
                    text.clear()
                    bare.clear()
                    inToken = false
                }

                c == '\'' -> {
                    val close = run.indexOf('\'', i + 1)
                    if (close < 0) throw Malformed("an unclosed single quote")
                    text.append(run, i + 1, close)
                    inToken = true
                    i = close
                }

                c == '"' -> {
                    i++
                    while (true) {
                        if (i >= run.length) throw Malformed("an unclosed double quote")
                        val d = run[i]
                        if (d == '"') break
                        if (d == '\\' && i + 1 < run.length && run[i + 1] in DOUBLE_QUOTE_ESCAPES) {
                            text.append(run[i + 1])
                            i += 2
                            continue
                        }
                        text.append(d)
                        i++
                    }
                    inToken = true
                }

                c == '\\' -> {
                    if (i + 1 < run.length) {
                        text.append(run[i + 1])
                        i++
                    }
                    inToken = true
                }

                else -> {
                    text.append(c)
                    bare.append(c)
                    inToken = true
                }
            }
            i++
        }
        if (inToken) tokens += Token(text.toString(), bare.toString())
        if (tokens.isEmpty()) throw Malformed("an empty command")

        tokens.forEach { token ->
            OPERATOR.find(token.bare)?.let {
                throw Malformed(
                    "the shell operator `${it.value}` — a gate is exactly one command, and nothing may mask " +
                        "its exit code",
                )
            }
            INTERPOLATION.findAll(token.text).forEach { match ->
                if (ENV_REFERENCE.matchEntire(match.value) == null) {
                    throw Malformed("the interpolation `${match.value}` — v1 has only `\${env.VAR}`")
                }
            }
        }
        return tokens
    }

    /** The argument vector of [run], unsubstituted. */
    fun argv(run: String): List<String> = tokenize(run).map { it.text }

    /** Every `${env.VAR}` name [run] references, in order of first appearance. */
    fun environmentReferences(run: String): List<String> =
        ENV_REFERENCE.findAll(run).map { it.groupValues[1] }.distinct().toList()

    /**
     * [argv] with every `${env.VAR}` replaced from [environment].
     *
     * @return the substituted vector, or the names that were unset. An unset variable
     *   is an infrastructure outcome: a gate must never run with an empty destination.
     */
    fun substitute(argv: List<String>, environment: Map<String, String>): Substitution {
        val missing = linkedSetOf<String>()
        val substituted = argv.map { argument ->
            ENV_REFERENCE.replace(argument) { match ->
                val name = match.groupValues[1]
                environment[name]?.takeIf { it.isNotEmpty() } ?: run {
                    missing += name
                    ""
                }
            }
        }
        return if (missing.isEmpty()) Substitution.Done(substituted) else Substitution.Unset(missing.toList())
    }

    sealed interface Substitution {
        data class Done(val argv: List<String>) : Substitution
        data class Unset(val names: List<String>) : Substitution
    }

    /**
     * Why [argv] bundles more than one independently runnable unit of work behind one
     * exit code, or null when it does not.
     *
     * Understood for the two tools the committed manifests use. For Gradle, "one task"
     * means one task **named on the command line** — what Gradle drags in through
     * `dependsOn` is Gradle's business. For xcodebuild, one action. Any other tool is
     * taken at its word: the runner cannot know its grammar, and guessing would refuse
     * legitimate gates.
     */
    fun bundling(argv: List<String>): String? {
        val tool = toolOf(argv)
        return when {
            isGradle(tool) -> gradleTasks(argv).takeIf { it.size > 1 }?.let { tasks ->
                "names ${tasks.size} Gradle tasks (${tasks.joinToString(" ")}); a gate is exactly one task"
            }

            tool == "xcodebuild" -> xcodebuildActions(argv).takeIf { it.size > 1 }?.let { actions ->
                "names ${actions.size} xcodebuild actions (${actions.joinToString(" ")}); a gate is exactly one action"
            }

            else -> null
        }
    }

    /** The tasks a Gradle command line names, in order: every argument that is not an option or an option's value. */
    fun gradleTasks(argv: List<String>): List<String> {
        val tasks = mutableListOf<String>()
        var i = 1
        while (i < argv.size) {
            val argument = argv[i]
            when {
                argument.startsWith("-") -> if ('=' !in argument && argument in GRADLE_VALUE_OPTIONS) i++
                else -> tasks += argument
            }
            i++
        }
        return tasks
    }

    /** Whether [argv] runs Gradle, by its executable's name. */
    fun isGradle(argv: List<String>): Boolean = isGradle(toolOf(argv))

    /**
     * How a `fresh: true` Gradle gate is made to execute rather than be satisfied from
     * a cache: `--no-build-cache`, `--rerun` on the gate's own task, and its own
     * test-result and test-report directories deleted first.
     *
     * The first and the last are the mechanism the AMPR-290 recon measured. `--rerun`
     * is the per-task form of `--rerun-tasks`: it makes the named task execute even
     * when its outputs are present and up to date — which covers a fresh gate whose
     * outputs are not under `build/test-results` (the eval replay) — while leaving
     * every upstream task free to come from the cache. That is exactly the schema's
     * line: the *verdict* may not be cached, its compilation may.
     *
     * Directories are derived from the default project layout: `:a:b:task` writes
     * `a/b/build/test-results/task`. A project that relocates its build directory
     * keeps its stale results, and `--rerun` is what still forces the execution.
     */
    fun freshGradle(argv: List<String>): FreshPlan {
        val tasks = gradleTasks(argv)
        val command = argv.toMutableList()
        if (NO_BUILD_CACHE !in command) command.add(1, NO_BUILD_CACHE)
        if (RERUN !in command) {
            tasks.lastOrNull()?.let { task -> command.add(command.lastIndexOf(task) + 1, RERUN) }
        }
        val deletions = tasks.flatMap { task ->
            val segments = task.split(':').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return@flatMap emptyList()
            val name = segments.last()
            val project = segments.dropLast(1).joinToString("/")
            val prefix = if (project.isEmpty()) "" else "$project/"
            listOf("${prefix}build/test-results/$name", "${prefix}build/reports/tests/$name")
        }
        return FreshPlan(command, deletions)
    }

    /**
     * @property argv The command to run instead.
     * @property deletions Directories to delete first, relative to the repository root.
     */
    data class FreshPlan(val argv: List<String>, val deletions: List<String>)

    private fun xcodebuildActions(argv: List<String>): List<String> =
        argv.withIndex().drop(1).filter { (index, argument) ->
            argument in XCODEBUILD_ACTIONS && (index == 1 || !argv[index - 1].startsWith("-"))
        }.map { it.value }

    private fun toolOf(argv: List<String>): String = argv.first().substringAfterLast('/')

    private fun isGradle(tool: String): Boolean = tool in GRADLE_EXECUTABLES

    const val NO_BUILD_CACHE: String = "--no-build-cache"

    const val RERUN: String = "--rerun"

    private val GRADLE_EXECUTABLES = setOf("gradlew", "gradle", "gradlew.bat")

    /** Gradle options whose value is the next argument, so it is not read as a task. */
    private val GRADLE_VALUE_OPTIONS = setOf(
        "-x", "--exclude-task",
        "--tests",
        "-p", "--project-dir",
        "-b", "--build-file",
        "-c", "--settings-file",
        "-I", "--init-script",
        "-g", "--gradle-user-home",
        "-D", "--system-prop",
        "-P", "--project-prop",
        "-F", "--dependency-verification",
        "-M", "--write-verification-metadata",
        "--include-build",
        "--project-cache-dir",
        "--max-workers",
        "--console",
        "--warning-mode",
        "--priority",
        "--update-locks",
        "--configuration-cache-problems",
    )

    private val XCODEBUILD_ACTIONS = setOf(
        "build",
        "build-for-testing",
        "analyze",
        "archive",
        "test",
        "test-without-building",
        "install",
        "installsrc",
        "clean",
        "docbuild",
    )

    private val DOUBLE_QUOTE_ESCAPES = setOf('"', '\\', '$', '`')

    private val OPERATOR = Regex("""&&|\|\||[;|&<>`]|\$\(""")

    private val INTERPOLATION = Regex("""\$\{[^}]*}""")

    private val ENV_REFERENCE = Regex("""\$\{env\.([A-Za-z_][A-Za-z0-9_]*)}""")
}
