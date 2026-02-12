package link.socket.ampere.animation.particle

import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticleTest {

    @Test
    fun `particle is alive when life is positive`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 0.5f,
            type = ParticleType.MOTE,
            glyph = '.'
        )
        assertTrue(particle.isAlive)
    }

    @Test
    fun `particle is dead when life is zero`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 0f,
            type = ParticleType.MOTE,
            glyph = '.'
        )
        assertFalse(particle.isAlive)
    }

    @Test
    fun `update moves particle by velocity`() {
        val particle = Particle(
            position = Vector2(0f, 0f),
            velocity = Vector2(10f, 5f),
            life = 1f,
            type = ParticleType.MOTE,
            glyph = '.'
        )

        particle.update(deltaTime = 0.1f, lifeDecay = 0f)

        assertEquals(1f, particle.position.x, 0.01f)
        assertEquals(0.5f, particle.position.y, 0.01f)
    }

    @Test
    fun `update decays life`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 1f,
            type = ParticleType.MOTE,
            glyph = '.'
        )

        particle.update(deltaTime = 0.1f, lifeDecay = 1f)

        assertEquals(0.9f, particle.life, 0.01f)
    }

    @Test
    fun `update applies drag to velocity`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2(10f, 0f),
            life = 1f,
            type = ParticleType.MOTE,
            glyph = '.'
        )

        particle.update(deltaTime = 0.1f, drag = 0.5f, lifeDecay = 0f)

        assertTrue(particle.velocity.x < 10f, "Drag should reduce velocity")
    }

    @Test
    fun `update applies gravity`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 1f,
            type = ParticleType.MOTE,
            glyph = '.'
        )
        val gravity = Vector2(0f, 10f)

        particle.update(deltaTime = 0.1f, gravity = gravity, lifeDecay = 0f)

        assertEquals(1f, particle.velocity.y, 0.01f)
    }

    @Test
    fun `update increments age`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 1f,
            type = ParticleType.MOTE,
            glyph = '.'
        )

        particle.update(deltaTime = 0.1f)
        particle.update(deltaTime = 0.1f)

        assertEquals(0.2f, particle.age, 0.01f)
    }

    @Test
    fun `life does not go negative`() {
        val particle = Particle(
            position = Vector2.ZERO,
            velocity = Vector2.ZERO,
            life = 0.05f,
            type = ParticleType.MOTE,
            glyph = '.'
        )

        particle.update(deltaTime = 1f, lifeDecay = 1f)

        assertEquals(0f, particle.life)
    }

    @Test
    fun `glyphs forType returns correct characters`() {
        // Test unicode mode
        assertTrue(Particle.Companion.Glyphs.forType(ParticleType.SPARK, 0.8f, true) in
            Particle.Companion.Glyphs.SPARK_UNICODE)

        // Test ASCII mode
        assertTrue(Particle.Companion.Glyphs.forType(ParticleType.SPARK, 0.8f, false) in
            Particle.Companion.Glyphs.SPARK_ASCII)
    }
}

class EmitterTest {

    @Test
    fun `BurstEmitter creates correct number of particles`() {
        val emitter = BurstEmitter(Random(12345))
        val config = EmitterConfig(type = ParticleType.SPARK)

        val particles = emitter.emit(10, Vector2.ZERO, config)

        assertEquals(10, particles.size)
    }

    @Test
    fun `BurstEmitter particles start at origin`() {
        val emitter = BurstEmitter(Random(12345))
        val config = EmitterConfig()
        val origin = Vector2(5f, 5f)

        val particles = emitter.emit(5, origin, config)

        particles.forEach { p ->
            assertEquals(5f, p.position.x, 0.01f)
            assertEquals(5f, p.position.y, 0.01f)
        }
    }

    @Test
    fun `BurstEmitter particles have velocity based on config`() {
        val emitter = BurstEmitter(Random(12345))
        val config = EmitterConfig(speed = 5f, speedVariance = 0f)

        val particles = emitter.emit(10, Vector2.ZERO, config)

        particles.forEach { p ->
            val speed = p.velocity.length()
            assertEquals(5f, speed, 0.5f)
        }
    }

    @Test
    fun `StreamEmitter creates directional particles`() {
        val emitter = StreamEmitter(Random(12345))
        val config = EmitterConfig(
            direction = 0f,  // Right
            spread = 10f,
            speed = 5f
        )

        val particles = emitter.emit(10, Vector2.ZERO, config)

        // Most particles should have positive X velocity (going right)
        val goingRight = particles.count { it.velocity.x > 0 }
        assertTrue(goingRight >= 8, "Most particles should go in configured direction")
    }

    @Test
    fun `AttractorEmitter particles are scattered around origin`() {
        val emitter = AttractorEmitter(
            random = Random(12345),
            target = Vector2(50f, 50f),
            scatterRadius = 10f
        )
        val config = EmitterConfig()
        val origin = Vector2(10f, 10f)

        val particles = emitter.emit(10, origin, config)

        // Particles should be scattered within radius
        particles.forEach { p ->
            val distance = (p.position - origin).length()
            assertTrue(distance <= 10f, "Particles should start within scatter radius")
        }
    }

    @Test
    fun `RippleEmitter creates ring pattern`() {
        val emitter = RippleEmitter(Random(12345))
        val config = EmitterConfig(speed = 5f)

        val particles = emitter.emit(24, Vector2.ZERO, config)

        assertEquals(24, particles.size)

        // Particles should have velocities pointing outward in all directions
        val positiveX = particles.count { it.velocity.x > 0 }
        val negativeX = particles.count { it.velocity.x < 0 }
        val positiveY = particles.count { it.velocity.y > 0 }
        val negativeY = particles.count { it.velocity.y < 0 }

        // Should be roughly evenly distributed
        assertTrue(positiveX > 5, "Should have particles going right")
        assertTrue(negativeX > 5, "Should have particles going left")
        assertTrue(positiveY > 5, "Should have particles going down")
        assertTrue(negativeY > 5, "Should have particles going up")
    }
}

class ParticleSystemTest {

    @Test
    fun `spawn adds particles to system`() {
        val system = ParticleSystem()
        val emitter = BurstEmitter()

        system.spawn(emitter, 10, Vector2.ZERO)

        assertEquals(10, system.count)
        assertTrue(system.hasParticles)
    }

    @Test
    fun `spawn respects max particles limit`() {
        val system = ParticleSystem(maxParticles = 5)
        val emitter = BurstEmitter()

        system.spawn(emitter, 10, Vector2.ZERO)

        assertEquals(5, system.count)
    }

    @Test
    fun `update removes dead particles`() {
        val system = ParticleSystem(lifeDecayRate = 100f)  // Fast decay
        val emitter = BurstEmitter()
        val config = EmitterConfig(life = 0.05f, lifeVariance = 0f)

        system.spawn(emitter, 10, Vector2.ZERO, config)
        system.update(0.1f)  // Should kill all particles

        assertEquals(0, system.count)
    }

    @Test
    fun `update moves particles`() {
        val system = ParticleSystem(lifeDecayRate = 0f)
        val particles = listOf(
            Particle(Vector2.ZERO, Vector2(10f, 0f), 1f, ParticleType.MOTE, '.')
        )
        system.addParticles(particles)

        system.update(0.1f)

        val updated = system.getParticles().first()
        assertTrue(updated.position.x > 0, "Particle should have moved")
    }

    @Test
    fun `clear removes all particles`() {
        val system = ParticleSystem()
        val emitter = BurstEmitter()

        system.spawn(emitter, 10, Vector2.ZERO)
        system.clear()

        assertEquals(0, system.count)
        assertFalse(system.hasParticles)
    }

    @Test
    fun `fadeAll reduces particle life`() {
        val system = ParticleSystem(lifeDecayRate = 0f)
        val config = EmitterConfig(life = 1f, lifeVariance = 0f)
        val emitter = BurstEmitter()

        system.spawn(emitter, 5, Vector2.ZERO, config)
        system.fadeAll(0.3f)

        system.getParticles().forEach { p ->
            assertEquals(0.7f, p.life, 0.01f)
        }
    }

    @Test
    fun `despawnNear removes particles in radius`() {
        val system = ParticleSystem(lifeDecayRate = 0f)

        val particles = listOf(
            Particle(Vector2(0f, 0f), Vector2.ZERO, 1f, ParticleType.MOTE, '.'),
            Particle(Vector2(1f, 0f), Vector2.ZERO, 1f, ParticleType.MOTE, '.'),
            Particle(Vector2(10f, 0f), Vector2.ZERO, 1f, ParticleType.MOTE, '.')
        )
        system.addParticles(particles)

        system.despawnNear(Vector2.ZERO, radius = 2f)

        assertEquals(1, system.count)
        assertEquals(10f, system.getParticles().first().position.x)
    }

    @Test
    fun `getParticlesNear returns particles within radius`() {
        val system = ParticleSystem()

        val particles = listOf(
            Particle(Vector2(5f, 5f), Vector2.ZERO, 1f, ParticleType.MOTE, '.'),
            Particle(Vector2(5.3f, 5f), Vector2.ZERO, 1f, ParticleType.MOTE, '.'),
            Particle(Vector2(10f, 10f), Vector2.ZERO, 1f, ParticleType.MOTE, '.')
        )
        system.addParticles(particles)

        val nearby = system.getParticlesNear(5, 5, radius = 1f)

        assertEquals(2, nearby.size)
    }

    @Test
    fun `getDensityAt accumulates particle contributions`() {
        val system = ParticleSystem()

        val particles = listOf(
            Particle(Vector2(5f, 5f), Vector2.ZERO, 1f, ParticleType.MOTE, '.'),
            Particle(Vector2(5f, 5f), Vector2.ZERO, 0.5f, ParticleType.MOTE, '.')
        )
        system.addParticles(particles)

        val density = system.getDensityAt(5, 5, maxInfluence = 0.3f)

        // 1.0 * 0.3 + 0.5 * 0.3 = 0.45
        assertEquals(0.45f, density, 0.01f)
    }

    @Test
    fun `updateSubstrate modifies substrate density`() {
        val system = ParticleSystem()
        val substrate = SubstrateState.create(20, 20, baseDensity = 0.2f)

        val particles = listOf(
            Particle(Vector2(10f, 10f), Vector2.ZERO, 1f, ParticleType.MOTE, '.')
        )
        system.addParticles(particles)

        val originalDensity = substrate.getDensity(10, 10)
        system.updateSubstrate(substrate, influence = 0.5f)
        val newDensity = substrate.getDensity(10, 10)

        assertTrue(newDensity > originalDensity,
            "Substrate density should increase from particle")
    }

    @Test
    fun `addAttractor affects particle velocities`() {
        val system = ParticleSystem(lifeDecayRate = 0f)

        val particle = Particle(Vector2(0f, 0f), Vector2(0f, 0f), 1f, ParticleType.MOTE, '.')
        system.addParticles(listOf(particle))

        system.addAttractor(Vector2(10f, 0f), strength = 1f)

        val updated = system.getParticles().first()
        assertTrue(updated.velocity.x > 0, "Particle should be attracted toward target")
    }

    @Test
    fun `createSparkBurst creates burst particles`() {
        val system = ParticleSystem.createSparkBurst(Vector2(10f, 10f), count = 15)

        assertEquals(15, system.count)
        system.getParticles().forEach { p ->
            assertEquals(ParticleType.SPARK, p.type)
        }
    }

    @Test
    fun `createStream creates directional stream`() {
        val system = ParticleSystem.createStream(
            Vector2.ZERO,
            direction = 90f,  // Down
            count = 10
        )

        assertEquals(10, system.count)
        system.getParticles().forEach { p ->
            assertEquals(ParticleType.TRAIL, p.type)
        }
    }

    @Test
    fun `createRipple creates ripple particles`() {
        val system = ParticleSystem.createRipple(Vector2.ZERO, count = 20)

        assertEquals(20, system.count)
        system.getParticles().forEach { p ->
            assertEquals(ParticleType.RIPPLE, p.type)
        }
    }

    @Test
    fun `system handles 500+ particles efficiently`() {
        val system = ParticleSystem(maxParticles = 1000)
        val emitter = BurstEmitter()

        // Spawn 500 particles
        system.spawn(emitter, 500, Vector2.ZERO)

        // Measure update time
        val startTime = System.nanoTime()
        repeat(20) {  // 20 updates (1 second at 20fps)
            system.update(0.05f)
        }
        val endTime = System.nanoTime()

        val durationMs = (endTime - startTime) / 1_000_000.0
        assertTrue(durationMs < 1000, "20 updates with 500 particles should take <1s, took ${durationMs}ms")
    }
}

class ParticleRendererTest {

    @Test
    fun `render creates correct grid dimensions`() {
        val renderer = ParticleRenderer(20, 10)
        val system = ParticleSystem()

        val lines = renderer.render(system)

        assertEquals(10, lines.size)
        lines.forEach { line ->
            assertEquals(20, line.length)
        }
    }

    @Test
    fun `render places particles on grid`() {
        val renderer = ParticleRenderer(20, 10, useUnicode = false)
        val system = ParticleSystem()

        val particle = Particle(Vector2(5f, 3f), Vector2.ZERO, 1f, ParticleType.SPARK, '*')
        system.addParticles(listOf(particle))

        val lines = renderer.render(system, background = '.')

        assertEquals('*', lines[3][5])
    }

    @Test
    fun `renderColored includes ANSI codes`() {
        val renderer = ParticleRenderer(20, 10)
        val system = ParticleSystem()

        val particle = Particle(Vector2(5f, 3f), Vector2.ZERO, 1f, ParticleType.SPARK, '*')
        system.addParticles(listOf(particle))

        val lines = renderer.renderColored(system)

        // Line should contain ANSI escape codes
        assertTrue(lines[3].contains("\u001B["), "Colored output should contain ANSI codes")
    }
}
