package link.socket.ampere.animation.emitter

import link.socket.ampere.animation.math.Vector3

/**
 * A running instance of an effect — anchored at a position and tracking elapsed time.
 */
data class EmitterInstance(
    val effect: EmitterEffect,
    val position: Vector3,
    val activatedAt: Float,
    val age: Float = 0f
) {
    val isExpired: Boolean get() = age >= effect.duration
}

/**
 * Manages the lifecycle of active emitter effect instances.
 *
 * The EmitterManager is the effect scheduler — it tracks what effects are alive,
 * advances their clocks, reaps expired ones, and aggregates their influences
 * at any query point in world space. The waveform renderer asks "what should I
 * do differently at (x, z)?" and gets back a combined EffectInfluence.
 */
class EmitterManager {

    private val _instances = mutableListOf<EmitterInstance>()

    /** Current active effect count. */
    val activeCount: Int get() = _instances.size

    /** Read-only view of active instances (for inspection/debugging). */
    val instances: List<EmitterInstance> get() = _instances.toList()

    /**
     * Fire an effect at a 3D position.
     *
     * @param effect The effect definition to instantiate
     * @param position World-space position where the effect originates
     * @param currentTime The current animation time (used to stamp activatedAt)
     */
    fun emit(effect: EmitterEffect, position: Vector3, currentTime: Float = 0f) {
        _instances.add(
            EmitterInstance(
                effect = effect,
                position = position,
                activatedAt = currentTime
            )
        )
    }

    /**
     * Advance all active effects by dt seconds and remove expired ones.
     *
     * @param dt Delta time in seconds
     */
    fun update(dt: Float) {
        val iterator = _instances.listIterator()
        while (iterator.hasNext()) {
            val instance = iterator.next()
            val updated = instance.copy(age = instance.age + dt)
            if (updated.isExpired) {
                iterator.remove()
            } else {
                iterator.set(updated)
            }
        }
    }

    /**
     * Query the combined influence of all active effects at a world-space point.
     *
     * The waveform surface lives on the XZ plane (Y is height), so we compute
     * distance from each effect center to the query point in the XZ plane.
     *
     * @param worldX X coordinate on the surface
     * @param worldZ Z coordinate on the surface
     * @return Combined EffectInfluence from all overlapping effects
     */
    fun aggregateInfluenceAt(worldX: Float, worldZ: Float): EffectInfluence {
        if (_instances.isEmpty()) return EffectInfluence.NONE

        var result = EffectInfluence.NONE
        for (instance in _instances) {
            val dx = worldX - instance.position.x
            val dz = worldZ - instance.position.z
            val distance = kotlin.math.sqrt(dx * dx + dz * dz)
            val influence = instance.effect.influence(distance, instance.age)
            if (influence.intensity > 0f) {
                result = result + influence
            }
        }
        return result
    }

    /**
     * Remove all active effects.
     */
    fun clear() {
        _instances.clear()
    }
}
