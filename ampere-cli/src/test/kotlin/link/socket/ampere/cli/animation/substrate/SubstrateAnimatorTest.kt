package link.socket.ampere.cli.animation.substrate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubstrateAnimatorTest {

    @Test
    fun `updateAmbient advances time`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(10, 10)

        val newState = animator.updateAmbient(state, deltaTime = 0.05f)

        assertEquals(0.05f, newState.time)
    }

    @Test
    fun `updateAmbient modifies density field`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(10, 10, baseDensity = 0.3f)

        val newState = animator.updateAmbient(state, deltaTime = 0.1f)

        // At least some densities should have changed
        var changedCount = 0
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                if (state.getDensity(x, y) != newState.getDensity(x, y)) {
                    changedCount++
                }
            }
        }
        assertTrue(changedCount > 0, "Expected at least some densities to change")
    }

    @Test
    fun `updateAmbient with hotspots increases density near hotspots`() {
        val animator = SubstrateAnimator(seed = 12345L, baseDensity = 0.2f)
        val state = SubstrateState.create(20, 20, baseDensity = 0.2f)
            .withHotspots(listOf(Point(10, 10)))

        val newState = animator.updateAmbient(state, deltaTime = 0.01f)

        // Density at hotspot should be higher than at edges
        val centerDensity = newState.getDensity(10, 10)
        val edgeDensity = newState.getDensity(0, 0)

        assertTrue(centerDensity > edgeDensity,
            "Center density ($centerDensity) should be higher than edge density ($edgeDensity)")
    }

    @Test
    fun `pulse increases density at center`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(20, 20, baseDensity = 0.2f)

        val newState = animator.pulse(state, center = Point(10, 10), intensity = 0.5f, radius = 5f)

        // Density at center should be higher
        val originalDensity = state.getDensity(10, 10)
        val pulsedDensity = newState.getDensity(10, 10)

        assertTrue(pulsedDensity > originalDensity,
            "Pulsed density ($pulsedDensity) should be higher than original ($originalDensity)")
    }

    @Test
    fun `pulse falls off with distance`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(20, 20, baseDensity = 0.2f)

        val newState = animator.pulse(state, center = Point(10, 10), intensity = 0.5f, radius = 5f)

        val centerDensity = newState.getDensity(10, 10)
        val nearbyDensity = newState.getDensity(12, 10)
        val farDensity = newState.getDensity(18, 10)

        assertTrue(centerDensity > nearbyDensity,
            "Center density ($centerDensity) should be higher than nearby ($nearbyDensity)")
        assertTrue(nearbyDensity > farDensity || farDensity == state.getDensity(18, 10),
            "Nearby density ($nearbyDensity) should be higher than far ($farDensity)")
    }

    @Test
    fun `ripple creates ring pattern`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(30, 30, baseDensity = 0.2f)

        val newState = animator.ripple(
            state,
            center = Point(15, 15),
            phase = 0.5f,
            maxRadius = 10f,
            intensity = 0.4f
        )

        // At phase 0.5 with maxRadius 10, the ring should be at radius 5
        // Points on the ring should have higher density than points away from it
        val originalDensity = state.getDensity(20, 15)
        val ringDensity = newState.getDensity(20, 15) // Should be on the ring

        assertTrue(ringDensity > originalDensity,
            "Ring density ($ringDensity) should be higher than original ($originalDensity)")
    }

    @Test
    fun `flowToward creates directional flow`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(20, 20)

        val newState = animator.flowToward(state, target = Point(10, 10), strength = 0.5f)

        // Check flow direction from corner (0, 0) toward target (10, 10)
        val flow = newState.getFlow(0, 0)
        assertTrue(flow.x > 0, "Flow X should be positive (toward right)")
        assertTrue(flow.y > 0, "Flow Y should be positive (toward down)")
    }

    @Test
    fun `createPath increases density along line`() {
        val animator = SubstrateAnimator(seed = 12345L)
        val state = SubstrateState.create(20, 10, baseDensity = 0.2f)

        val newState = animator.createPath(
            state,
            from = Point(2, 5),
            to = Point(17, 5),
            intensity = 0.5f,
            width = 2f
        )

        // Check points along the path
        val originalMidpoint = state.getDensity(10, 5)
        val newMidpoint = newState.getDensity(10, 5)

        assertTrue(newMidpoint > originalMidpoint,
            "Path midpoint density ($newMidpoint) should be higher than original ($originalMidpoint)")

        // Check that points far from path are unchanged
        val farPoint = newState.getDensity(10, 0)
        assertEquals(state.getDensity(10, 0), farPoint,
            "Points far from path should be unchanged")
    }

    @Test
    fun `clear resets substrate to base density`() {
        val animator = SubstrateAnimator(seed = 12345L, baseDensity = 0.4f)
        val state = SubstrateState.create(10, 10, baseDensity = 0.4f)
            .withHotspots(listOf(Point(5, 5)))

        // Modify the state
        val modifiedState = animator.pulse(state, Point(5, 5), 0.5f, 3f)

        // Clear it
        val clearedState = animator.clear(modifiedState)

        // All densities should be back to base
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                assertEquals(0.4f, clearedState.getDensity(x, y),
                    "Density at ($x, $y) should be base density")
            }
        }

        // Flow should be reset
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                assertEquals(Vector2.ZERO, clearedState.getFlow(x, y),
                    "Flow at ($x, $y) should be zero")
            }
        }

        // Hotspots should be cleared
        assertEquals(emptyList(), clearedState.activityHotspots)
    }

    @Test
    fun `densities stay in valid range after multiple operations`() {
        val animator = SubstrateAnimator(seed = 12345L)
        var state = SubstrateState.create(20, 20, baseDensity = 0.5f)

        // Apply multiple operations
        repeat(10) {
            state = animator.updateAmbient(state, 0.1f)
            state = animator.pulse(state, Point(10, 10), 0.3f, 5f)
        }

        // All densities should be in [0, 1]
        for (y in 0 until 20) {
            for (x in 0 until 20) {
                val density = state.getDensity(x, y)
                assertTrue(density >= 0f && density <= 1f,
                    "Density at ($x, $y) should be in [0, 1], was $density")
            }
        }
    }
}

class PerlinNoiseTest {

    @Test
    fun `noise values are in expected range`() {
        val noise = PerlinNoise(seed = 12345L)

        repeat(1000) {
            val x = (it % 100) * 0.1f
            val y = (it / 100) * 0.1f
            val value = noise.sample(x, y)

            assertTrue(value >= -1f && value <= 1f,
                "Noise value should be in [-1, 1], was $value at ($x, $y)")
        }
    }

    @Test
    fun `noise is deterministic with same seed`() {
        val noise1 = PerlinNoise(seed = 12345L)
        val noise2 = PerlinNoise(seed = 12345L)

        repeat(100) {
            val x = it * 0.1f
            val y = it * 0.05f
            assertEquals(noise1.sample(x, y), noise2.sample(x, y),
                "Noise should be deterministic with same seed")
        }
    }

    @Test
    fun `noise is different with different seeds`() {
        val noise1 = PerlinNoise(seed = 12345L)
        val noise2 = PerlinNoise(seed = 54321L)

        var sameCount = 0
        repeat(100) {
            val x = it * 0.1f
            val y = it * 0.05f
            if (noise1.sample(x, y) == noise2.sample(x, y)) {
                sameCount++
            }
        }

        assertTrue(sameCount < 10, "Different seeds should produce different noise")
    }

    @Test
    fun `3D noise varies with z coordinate`() {
        val noise = PerlinNoise(seed = 12345L)

        // Test multiple points to ensure 3D noise varies with z
        var differentCount = 0
        for (i in 0 until 10) {
            val x = i * 0.5f + 0.5f
            val y = i * 0.3f + 0.5f
            val v1 = noise.sample(x, y, 0f)
            val v2 = noise.sample(x, y, 1f)
            if (v1 != v2) {
                differentCount++
            }
        }

        assertTrue(differentCount > 5,
            "3D noise should vary with z coordinate (got $differentCount different values out of 10)")
    }

    @Test
    fun `fbm combines multiple octaves`() {
        val noise = PerlinNoise(seed = 12345L)

        // Test multiple points to ensure FBM differs from simple noise
        var differentCount = 0
        for (i in 0 until 10) {
            val x = i * 0.7f + 0.5f
            val y = i * 0.3f + 0.5f
            val simple = noise.sample(x, y)
            val fbm = noise.fbm(x, y, octaves = 4, persistence = 0.5f)
            if (simple != fbm) {
                differentCount++
            }
            // FBM should still be in reasonable range
            assertTrue(fbm >= -1f && fbm <= 1f,
                "FBM value should be in [-1, 1], was $fbm at ($x, $y)")
        }

        assertTrue(differentCount > 5,
            "FBM should usually differ from simple noise (got $differentCount different values out of 10)")
    }
}
