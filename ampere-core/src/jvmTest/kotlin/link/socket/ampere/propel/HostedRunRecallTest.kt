package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.RecallSource
import link.socket.ampere.memory.Recalled
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig

/**
 * Task 3 of AMPR-394: what a hosted run's RECALL finds, and where it goes.
 *
 * Driven through the production wiring like [HostedRunTest] — real doors onto one
 * store, real phase services behind a counting fake transport — because the claim
 * under test is about a prompt a model is actually sent and an event a store actually
 * holds, not about a renderer in isolation.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunRecallTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(door.database, door.driver),
            outcomes = OutcomeMemoryRepositoryImpl(door.database),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    /**
     * The headline claim: both halves of RECALL reach the model, and one event says so.
     *
     * The corpus returns four items against a per-source limit of three, so this also
     * pins which three — the source's own first three, not the three that happen to
     * score highest once Ampere's incomparable relevance numbers are mixed in.
     */
    @Test
    fun `a memory entry and a corpus hit both reach the think prompt and the one KnowledgeRecalled`() =
        runBlocking<Unit> {
            storeMemory(approach = MEMORY_APPROACH, learnings = MEMORY_LEARNINGS)
            val corpus = FakeCorpus(
                Recalled(id = "doc-1", title = DOC_TITLE, text = DOC_TEXT, score = 0.9, sourceId = DOC_SOURCE),
                Recalled(id = "doc-2", title = "Second note", text = "also saved", score = 0.5),
                Recalled(id = "doc-3", title = "Third note", text = "also saved", score = 0.4),
                Recalled(id = "doc-4", title = DROPPED_TITLE, text = "past the limit", score = 0.3),
            )

            val host = planningSeat()
            val doer = seat(DOER_ID, DOER_ROLE) { "thought about it" }
            val run = openRun(host, doer, RunPolicy(recall = RecallPolicy(sources = listOf(corpus))))
            run.execute()
            run.close()

            // --- the source was asked the run's question, once -----------------------
            val asked = assertNotNull(corpus.calls.singleOrNull(), "one run asks a source once")
            assertEquals(GOAL_TEXT, asked.intent, "the intent is what the run is for")
            assertEquals(setOf(SCOPE), asked.scopes, "scoped by the seat's declared scopes")
            assertEquals(3, asked.limit, "the default per-source limit")

            // --- both halves are in the host's PLAN prompt ---------------------------
            val section = recalledSection(assertNotNull(host.planPrompt(), "the host planned"))
            assertTrue(section.contains(MEMORY_APPROACH), "the memory entry's approach: $section")
            assertTrue(section.contains(MEMORY_LEARNINGS), "and its learnings: $section")
            assertTrue(section.contains(DOC_TITLE), "the corpus item's title: $section")
            assertTrue(section.contains(DOC_TEXT), "and its text: $section")
            assertTrue(
                !section.contains(DROPPED_TITLE),
                "the fourth item was past the per-source limit: $section",
            )
            assertTrue(
                section.contains("never repeat it back"),
                "the section tells the model what to do with it: $section",
            )

            // --- and in every other seat's, which is where a step is answered --------
            val doerPrompt = assertNotNull(doer.transport.prompts.firstOrNull(), "the doer thought")
            assertTrue(
                recalledSection(doerPrompt).contains(DOC_TITLE),
                "the seat that ran the step was told what the run recalled: $doerPrompt",
            )

            // --- one event, summarising both ----------------------------------------
            val recalls = rows(run.runId).mapNotNull { decode(it) as? MemoryEvent.KnowledgeRecalled }
            val event = assertNotNull(
                recalls.singleOrNull(),
                "one KnowledgeRecalled for the phase, not one per half; got ${recalls.size}",
            )
            assertEquals(HOST_ID, event.eventSource.getIdentifier(), "published through the host's door")
            assertEquals(run.runId, event.runId)
            assertEquals(4, event.resultsFound, "one memory entry and three of the four corpus items")
            assertEquals(setOf(SCOPE), event.context.tags, "the scopes are the query's tags")
            assertEquals(MemoryTaskTypes.GENERIC, event.context.taskType, "the bucket LEARN also files under")
            assertTrue(
                event.retrievedKnowledge.any { it.approach == MEMORY_APPROACH },
                "the memory half is summarised: ${event.retrievedKnowledge}",
            )
            val corpusSummary = assertNotNull(
                event.retrievedKnowledge.firstOrNull { it.approach == DOC_TITLE },
                "the corpus half is summarised: ${event.retrievedKnowledge}",
            )
            assertEquals(
                KnowledgeType.FROM_TASK,
                corpusSummary.knowledgeType,
                "a corpus item is typed FROM_TASK, which is how a reader tells the halves apart",
            )
            assertEquals(DOC_SOURCE, corpusSummary.sourceId, "and carries the document it came out of")
            assertTrue(
                event.topKnowledgeIds.containsAll(listOf("doc-1", "doc-2", "doc-3")),
                "the ids of both halves: ${event.topKnowledgeIds}",
            )

            // --- nothing recalled was stored ----------------------------------------
            assertTrue(
                store.knowledge.findKnowledgeByType(KnowledgeType.FROM_TASK, limit = 10)
                    .getOrThrow()
                    .none { it.approach == DOC_TITLE },
                "a recalled document stays the consumer's; FROM_TASK is a label, not a write",
            )
        }

    /**
     * A run that recalled nothing renders no heading.
     *
     * The event still goes out: "entered and found nothing" and "skipped" are different
     * facts about a phase, and only the first one is true here.
     */
    @Test
    fun `a run with no hits renders no section`() = runBlocking<Unit> {
        val host = planningSeat(scopes = emptySet())
        val doer = seat(DOER_ID, DOER_ROLE) { "thought about it" }

        val run = openRun(host, doer)
        run.execute()
        run.close()

        val planPrompt = assertNotNull(host.planPrompt(), "the host planned")
        assertTrue(
            !planPrompt.contains(RECALLED_HEADING),
            "an empty recall has no section to render: $planPrompt",
        )
        assertTrue(
            host.transport.prompts.none { it.contains(RECALLED_HEADING) },
            "nor does any other call of the run",
        )
        val recalls = rows(run.runId).mapNotNull { decode(it) as? MemoryEvent.KnowledgeRecalled }
        assertEquals(0, assertNotNull(recalls.singleOrNull()).resultsFound, "the phase still reports")
    }

    /** Scopes are what makes a corpus query a recall, so without them there is none. */
    @Test
    fun `a seat with no scopes recalls from memory only`() = runBlocking<Unit> {
        storeMemory(approach = MEMORY_APPROACH, learnings = MEMORY_LEARNINGS)
        val corpus = FakeCorpus(Recalled(id = "doc-1", title = DOC_TITLE, text = DOC_TEXT, score = 0.9))

        val host = planningSeat(scopes = emptySet())
        val doer = seat(DOER_ID, DOER_ROLE) { "thought about it" }
        val run = openRun(host, doer, RunPolicy(recall = RecallPolicy(sources = listOf(corpus))))
        run.execute()
        run.close()

        assertTrue(corpus.calls.isEmpty(), "an unscoped corpus query is a request for the whole corpus")
        val section = recalledSection(assertNotNull(host.planPrompt(), "the host planned"))
        assertTrue(section.contains(MEMORY_APPROACH), "memory still recalls: $section")
        assertTrue(!section.contains(DOC_TITLE))
    }

    @Test
    fun `the clip lengths are honoured`() = runBlocking<Unit> {
        storeMemory(approach = LONG_APPROACH, learnings = LONG_LEARNINGS)
        val corpus = FakeCorpus(
            Recalled(id = "doc-1", title = LONG_TITLE, text = LONG_TEXT, score = 0.9),
        )

        val host = planningSeat()
        val doer = seat(DOER_ID, DOER_ROLE) { "thought about it" }
        val run = openRun(
            host,
            doer,
            RunPolicy(recall = RecallPolicy(sources = listOf(corpus), titleClip = 12, textClip = 20)),
        )
        run.execute()
        run.close()

        val section = recalledSection(assertNotNull(host.planPrompt(), "the host planned"))
        listOf(LONG_APPROACH to 12, LONG_TITLE to 12, LONG_LEARNINGS to 20, LONG_TEXT to 20)
            .forEach { (text, clip) ->
                val oneLine = text.replace(Regex("""\s+"""), " ")
                assertTrue(
                    section.contains(oneLine.take(clip) + "…"),
                    "'${oneLine.take(clip)}…' should be in the section: $section",
                )
                assertTrue(
                    !section.contains(oneLine.take(clip + 1)),
                    "and nothing past $clip characters of it: $section",
                )
            }
        assertEquals(
            2,
            section.lines().count { it.startsWith("- ") },
            "one line per item, however many newlines the item had: $section",
        )
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** A [RecallSource] that answers the same items and records what it was asked. */
    private class FakeCorpus(private vararg val items: Recalled) : RecallSource {

        val calls: MutableList<Ask> = CopyOnWriteArrayList()

        override suspend fun recall(intent: String, scopes: Set<String>, limit: Int): List<Recalled> {
            calls += Ask(intent, scopes, limit)
            return items.toList()
        }
    }

    private data class Ask(val intent: String, val scopes: Set<String>, val limit: Int)

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        /** The PLAN call's prompt, told by the schema its own prompt asks for. */
        fun planPrompt(): String? = transport.prompts.firstOrNull { it.contains("\"steps\"") }
    }

    /** The host seat: plans one tool-less step for the doer, so both seats think. */
    private fun planningSeat(scopes: Set<String> = setOf(SCOPE)): Seat =
        seat(HOST_ID, HOST_ROLE, scopes) {
            planJson(planStep("Think about the retry policy", null, DOER_ROLE.value))
        }

    private fun seat(
        id: String,
        role: RoleId,
        scopes: Set<String> = emptySet(),
        answering: () -> String,
    ): Seat {
        val transport = CountingUpstreamLlmClient(
            if (role == HOST_ROLE) hostAnswers(answering) else fixedAnswer(answering),
        )
        return Seat(
            descriptor = HostedAgent(
                id = id,
                role = role,
                charter = "You are the ${role.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
                recallScopes = scopes,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        policy: RunPolicy = RunPolicy(),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            roles = listOf(
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                RoleConfig(id = DOER_ROLE, title = "Doer", instructions = PROMPT),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT),
        policy = policy,
    )

    private suspend fun storeMemory(approach: String, learnings: String) {
        store.knowledge.storeKnowledge(
            knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-under-test",
                approach = approach,
                learnings = learnings,
                timestamp = Clock.System.now(),
            ),
            tags = listOf(SCOPE),
            taskType = MemoryTaskTypes.GENERIC,
            runId = "an-earlier-run",
        ).getOrThrow()
    }

    /**
     * The "# Recalled context" section of [prompt], and nothing else.
     *
     * Sliced rather than searched whole because the PLAN prompt also renders the memory
     * half as the planner's "Relevant past experiences" (AMPR-388): an assertion over
     * the whole prompt would pass on that one and prove nothing about this section.
     */
    private fun recalledSection(prompt: String): String {
        assertTrue(prompt.contains(RECALLED_HEADING), "no recalled section in: $prompt")
        return prompt.substringAfter(RECALLED_HEADING).substringBefore("# Cognitive Context")
    }

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-recall-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val SCOPE = "recipes"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val RECALLED_HEADING = "# Recalled context"

        const val MEMORY_APPROACH = "Retried the upload with a capped backoff"
        const val MEMORY_LEARNINGS = "Three attempts was enough; the fourth never helped"
        const val DOC_TITLE = "Uploader runbook"
        const val DOC_TEXT = "The uploader retries on 503 and gives up on 400"
        const val DOC_SOURCE = "runbooks/uploader.md"
        const val DROPPED_TITLE = "Fourth note"

        const val LONG_APPROACH = "An approach whose description runs on well past the clip"
        const val LONG_LEARNINGS = "A learning\nsplit over lines\nand far longer than the clip allows"
        const val LONG_TITLE = "A document title that also runs on past the clip"
        const val LONG_TEXT = "Saved text\nwith newlines in it\nand plenty more characters than fit"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
