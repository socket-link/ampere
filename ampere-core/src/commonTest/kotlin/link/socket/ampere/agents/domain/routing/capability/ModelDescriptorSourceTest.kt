package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * The injectable-catalog contract (AMPR-230): a consumer supplies a
 * [ModelDescriptorSource] and refreshes model→rung assignments at runtime.
 */
class ModelDescriptorSourceTest {

    private fun descriptor(
        name: String,
        rung: CapabilityRung,
    ): ModelDescriptor = ModelDescriptor(
        modelName = name,
        providerId = AIProvider_Anthropic.id,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.NORMAL,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT,
        rung = rung,
    )

    // ── Default construction ──────────────────────────────────────────────────

    @Test
    fun `no-argument construction holds the bundled catalog`() = runTest {
        val registry = InMemoryModelDescriptorRegistry()

        assertEquals(
            InMemoryModelDescriptorRegistry.defaultModelDescriptors().toSet(),
            registry.all().toSet(),
        )
    }

    @Test
    fun `default source echoes the seed rather than swapping in the bundled catalog`() = runTest {
        val seed = listOf(descriptor("only-model", CapabilityRung.TWO))
        val registry = InMemoryModelDescriptorRegistry(seed = seed)

        assertTrue(registry.refresh().isSuccess)

        assertEquals(seed, registry.all())
    }

    @Test
    fun `refreshing a default registry reloads the same bundled catalog`() = runTest {
        val registry = InMemoryModelDescriptorRegistry()
        val before = registry.all().toSet()

        assertTrue(registry.refresh().isSuccess)

        assertEquals(before, registry.all().toSet())
        assertEquals(DefaultModelDescriptorSource.load().getOrThrow().toSet(), before)
    }

    // ── Refresh replaces the catalog ──────────────────────────────────────────

    @Test
    fun `refresh picks up a changed rung for an existing model`() = runTest {
        var rung = CapabilityRung.TWO
        val source = ModelDescriptorSource { Result.success(listOf(descriptor("shifting-model", rung))) }
        val registry = InMemoryModelDescriptorRegistry(seed = emptyList(), source = source)

        assertTrue(registry.refresh().isSuccess)
        assertEquals(CapabilityRung.TWO, registry.descriptorFor("shifting-model")?.rung)

        rung = CapabilityRung.FOUR
        assertTrue(registry.refresh().isSuccess)

        assertEquals(CapabilityRung.FOUR, registry.descriptorFor("shifting-model")?.rung)
    }

    @Test
    fun `refresh drops models the source no longer lists`() = runTest {
        var catalog = listOf(descriptor("retired-model", CapabilityRung.THREE))
        val registry = InMemoryModelDescriptorRegistry(
            seed = catalog,
            source = ModelDescriptorSource { Result.success(catalog) },
        )

        catalog = listOf(descriptor("successor-model", CapabilityRung.THREE))
        assertTrue(registry.refresh().isSuccess)

        assertNull(registry.descriptorFor("retired-model"), "replacement is wholesale, not a merge")
        assertNotNull(registry.descriptorFor("successor-model"))
    }

    @Test
    fun `register still upserts a single descriptor without touching the rest`() = runTest {
        val registry = InMemoryModelDescriptorRegistry(
            seed = listOf(descriptor("kept", CapabilityRung.ONE)),
        )

        registry.register(descriptor("added", CapabilityRung.FOUR))

        assertEquals(CapabilityRung.ONE, registry.descriptorFor("kept")?.rung)
        assertEquals(CapabilityRung.FOUR, registry.descriptorFor("added")?.rung)
    }

    // ── Failure leaves the previous catalog intact ────────────────────────────

    @Test
    fun `a failed load surfaces the error and never empties the registry`() = runTest {
        val boom = IllegalStateException("catalog endpoint unreachable")
        val seed = listOf(descriptor("survivor", CapabilityRung.THREE))
        val registry = InMemoryModelDescriptorRegistry(
            seed = seed,
            source = { Result.failure(boom) },
        )

        val result = registry.refresh()

        assertEquals(boom, result.exceptionOrNull())
        assertEquals(seed, registry.all())
        assertEquals(CapabilityRung.THREE, registry.descriptorFor("survivor")?.rung)
    }

    @Test
    fun `a source that throws is caught as a failure rather than propagated`() = runTest {
        val seed = listOf(descriptor("survivor", CapabilityRung.THREE))
        val registry = InMemoryModelDescriptorRegistry(
            seed = seed,
            source = { throw IllegalArgumentException("malformed catalog") },
        )

        val result = registry.refresh()

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(seed, registry.all())
    }

    @Test
    fun `a catalog with duplicate model names is rejected rather than silently deduplicated`() = runTest {
        // AMPR-233 rejects duplicates in the seed; a consumer-supplied catalog is
        // held to the same rule, but surfaces as a failure rather than a throw so
        // the live catalog survives a bad load.
        val seed = listOf(descriptor("survivor", CapabilityRung.THREE))
        val registry = InMemoryModelDescriptorRegistry(
            seed = seed,
            source = {
                Result.success(
                    listOf(
                        descriptor("collides", CapabilityRung.ONE),
                        descriptor("collides", CapabilityRung.FOUR),
                    ),
                )
            },
        )

        val result = registry.refresh()

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("collides") == true,
            "the failure should name the colliding model",
        )
        assertEquals(seed, registry.all(), "a rejected catalog must not disturb the live one")
    }

    @Test
    fun `an empty successful load is taken at its word`() = runTest {
        val registry = InMemoryModelDescriptorRegistry(
            seed = listOf(descriptor("doomed", CapabilityRung.THREE)),
            source = { Result.success(emptyList()) },
        )

        assertTrue(registry.refresh().isSuccess)

        assertEquals(emptyList<ModelDescriptor>(), registry.all())
    }

    // ── from() ────────────────────────────────────────────────────────────────

    @Test
    fun `from loads the catalog before handing back a registry`() = runTest {
        val catalog = listOf(descriptor("consumer-model", CapabilityRung.FOUR))

        val registry = InMemoryModelDescriptorRegistry.from { Result.success(catalog) }.getOrThrow()

        assertEquals(catalog, registry.all())
    }

    @Test
    fun `from surfaces a load failure instead of an empty registry`() = runTest {
        val boom = IllegalStateException("no catalog")

        val result = InMemoryModelDescriptorRegistry.from { Result.failure(boom) }

        assertEquals(boom, result.exceptionOrNull())
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    fun `concurrent refresh and reads never observe a half-replaced catalog`() = runBlocking {
        // Two disjoint catalogs of equal size. A read that comes up short, or
        // that mixes names from both, saw a partial swap.
        val even = (0 until 24).map { descriptor("even-$it", CapabilityRung.TWO) }
        val odd = (0 until 24).map { descriptor("odd-$it", CapabilityRung.THREE) }
        var flip = false
        val registry = InMemoryModelDescriptorRegistry(
            seed = even,
            source = {
                flip = !flip
                Result.success(if (flip) odd else even)
            },
        )

        val snapshots = withContext(Dispatchers.Default) {
            val writers = List(8) { launch { repeat(50) { registry.refresh() } } }
            val readers = List(8) { async { List(50) { registry.all() } } }

            writers.forEach { it.join() }
            readers.awaitAll().flatten()
        }

        snapshots.forEach { snapshot ->
            assertEquals(24, snapshot.size, "snapshot was partially replaced: $snapshot")
            val catalogs = snapshot.map { it.modelName.substringBefore('-') }.toSet()
            assertEquals(1, catalogs.size, "snapshot mixed two catalogs: $catalogs")
        }
    }

    // ── The point of all this: routing changes without rebuilding the relay ───

    @Test
    fun `refresh changes which model satisfies a floor`() = runTest {
        var catalog = listOf(
            descriptor("cheap-model", CapabilityRung.ONE),
            ModelDescriptor(
                modelName = "capable-model",
                providerId = AIProvider_Google.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.HIGH,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT,
                rung = CapabilityRung.TWO,
            ),
        )
        val registry = InMemoryModelDescriptorRegistry(
            seed = catalog,
            source = { Result.success(catalog) },
        )
        val floor = CapabilityRequirement(minRung = CapabilityRung.FOUR)

        assertTrue(
            registry.all().none { it.satisfies(floor) },
            "no model clears a rung FOUR floor yet",
        )

        // The consumer re-rates capable-model without a framework release.
        catalog = catalog.map {
            if (it.modelName == "capable-model") it.copy(rung = CapabilityRung.FOUR) else it
        }
        assertTrue(registry.refresh().isSuccess)

        assertEquals(
            listOf("capable-model"),
            registry.all().filter { it.satisfies(floor) }.map { it.modelName },
        )
    }
}
