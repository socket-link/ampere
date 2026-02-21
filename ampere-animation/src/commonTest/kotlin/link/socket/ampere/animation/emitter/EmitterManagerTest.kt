package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.render.CognitiveColorRamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmitterManagerTest {

    @Test
    fun `newly created manager has zero active effects`() {
        val manager = EmitterManager()
        assertEquals(0, manager.activeCount)
    }

    @Test
    fun `emit adds an active instance`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(), Vector3.ZERO)
        assertEquals(1, manager.activeCount)
    }

    @Test
    fun `emit multiple effects tracks all`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(), Vector3.ZERO)
        manager.emit(EmitterEffect.HeightPulse(), Vector3(2f, 0f, 2f))
        manager.emit(EmitterEffect.Confetti(), Vector3(-1f, 0f, 1f))
        assertEquals(3, manager.activeCount)
    }

    @Test
    fun `update advances age of instances`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(duration = 1f), Vector3.ZERO)
        manager.update(0.3f)
        val instance = manager.instances.first()
        assertEquals(0.3f, instance.age, 0.001f)
    }

    @Test
    fun `update removes expired instances`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(duration = 0.5f), Vector3.ZERO)
        manager.update(0.6f)
        assertEquals(0, manager.activeCount)
    }

    @Test
    fun `update retains unexpired instances`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(duration = 1f), Vector3.ZERO)
        manager.update(0.3f)
        assertEquals(1, manager.activeCount)
    }

    @Test
    fun `update removes only expired among mixed effects`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(duration = 0.5f), Vector3.ZERO)
        manager.emit(EmitterEffect.Turbulence(duration = 3f), Vector3(1f, 0f, 0f))
        manager.update(0.6f)
        assertEquals(1, manager.activeCount)
        assertEquals("turbulence", manager.instances.first().effect.name)
    }

    @Test
    fun `aggregateInfluenceAt returns NONE when empty`() {
        val manager = EmitterManager()
        assertEquals(EffectInfluence.NONE, manager.aggregateInfluenceAt(0f, 0f))
    }

    @Test
    fun `aggregateInfluenceAt returns influence near active effect`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.HeightPulse(duration = 2f, radius = 5f), Vector3.ZERO)
        manager.update(0.3f)
        val influence = manager.aggregateInfluenceAt(0f, 0f)
        assertTrue(influence.intensity > 0f)
        assertTrue(influence.heightModifier > 0f)
    }

    @Test
    fun `aggregateInfluenceAt returns NONE far from effects`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(radius = 2f), Vector3.ZERO)
        manager.update(0.1f)
        val influence = manager.aggregateInfluenceAt(100f, 100f)
        assertEquals(EffectInfluence.NONE, influence)
    }

    @Test
    fun `aggregateInfluenceAt combines overlapping effects`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.HeightPulse(duration = 2f, radius = 5f), Vector3.ZERO)
        manager.emit(EmitterEffect.HeightPulse(duration = 2f, radius = 5f), Vector3(1f, 0f, 0f))
        manager.update(0.3f)

        val singleInfluence = run {
            val m = EmitterManager()
            m.emit(EmitterEffect.HeightPulse(duration = 2f, radius = 5f), Vector3.ZERO)
            m.update(0.3f)
            m.aggregateInfluenceAt(0.5f, 0f)
        }
        val combinedInfluence = manager.aggregateInfluenceAt(0.5f, 0f)

        // Combined height should be >= single (two overlapping pulses)
        assertTrue(combinedInfluence.heightModifier >= singleInfluence.heightModifier)
    }

    @Test
    fun `aggregateInfluenceAt uses XZ distance ignoring Y`() {
        val manager = EmitterManager()
        // Effect at Y=10, but same XZ as query point
        manager.emit(EmitterEffect.HeightPulse(duration = 2f, radius = 5f), Vector3(0f, 10f, 0f))
        manager.update(0.3f)
        val influence = manager.aggregateInfluenceAt(0f, 0f)
        // Should still have influence because XZ distance is 0
        assertTrue(influence.intensity > 0f)
    }

    @Test
    fun `clear removes all instances`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(), Vector3.ZERO)
        manager.emit(EmitterEffect.Turbulence(), Vector3(1f, 0f, 0f))
        manager.clear()
        assertEquals(0, manager.activeCount)
    }

    @Test
    fun `emit records activatedAt timestamp`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.SparkBurst(), Vector3.ZERO, currentTime = 5.5f)
        assertEquals(5.5f, manager.instances.first().activatedAt)
    }

    @Test
    fun `instance isExpired when age reaches duration`() {
        val instance = EmitterInstance(
            effect = EmitterEffect.SparkBurst(duration = 1f),
            position = Vector3.ZERO,
            activatedAt = 0f,
            age = 1f
        )
        assertTrue(instance.isExpired)
    }

    @Test
    fun `instance not expired when age below duration`() {
        val instance = EmitterInstance(
            effect = EmitterEffect.SparkBurst(duration = 1f),
            position = Vector3.ZERO,
            activatedAt = 0f,
            age = 0.5f
        )
        assertTrue(!instance.isExpired)
    }

    @Test
    fun `multiple update steps accumulate age`() {
        val manager = EmitterManager()
        manager.emit(EmitterEffect.Turbulence(duration = 5f), Vector3.ZERO)
        manager.update(0.1f)
        manager.update(0.2f)
        manager.update(0.3f)
        assertEquals(0.6f, manager.instances.first().age, 0.001f)
    }
}
