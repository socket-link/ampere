package link.socket.ampere.animation.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 4x4 transformation matrix for 3D projection.
 * Stored as column-major float array for performance.
 *
 * Column-major layout: data[col * 4 + row]
 */
class Matrix4(val data: FloatArray = FloatArray(16)) {

    /** Multiply two matrices. */
    operator fun times(other: Matrix4): Matrix4 {
        val result = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += data[k * 4 + row] * other.data[col * 4 + k]
                }
                result[col * 4 + row] = sum
            }
        }
        return Matrix4(result)
    }

    /** Apply full transform including perspective divide. */
    fun transform(point: Vector3): Vector3 {
        val x = data[0] * point.x + data[4] * point.y + data[8] * point.z + data[12]
        val y = data[1] * point.x + data[5] * point.y + data[9] * point.z + data[13]
        val z = data[2] * point.x + data[6] * point.y + data[10] * point.z + data[14]
        val w = data[3] * point.x + data[7] * point.y + data[11] * point.z + data[15]
        return if (w != 0f && w != 1f) {
            Vector3(x / w, y / w, z / w)
        } else {
            Vector3(x, y, z)
        }
    }

    /** Transform a direction vector (no translation, no perspective divide). */
    fun transformDirection(dir: Vector3): Vector3 {
        return Vector3(
            data[0] * dir.x + data[4] * dir.y + data[8] * dir.z,
            data[1] * dir.x + data[5] * dir.y + data[9] * dir.z,
            data[2] * dir.x + data[6] * dir.y + data[10] * dir.z
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix4) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Matrix4(${data.toList()})"

    companion object {
        fun identity(): Matrix4 {
            val d = FloatArray(16)
            d[0] = 1f; d[5] = 1f; d[10] = 1f; d[15] = 1f
            return Matrix4(d)
        }

        /**
         * Build a view matrix looking from [eye] toward [target].
         */
        fun lookAt(eye: Vector3, target: Vector3, up: Vector3): Matrix4 {
            val forward = (target - eye).normalized()  // Camera looks along +forward
            val right = forward.cross(up).normalized()
            val camUp = right.cross(forward)

            val d = FloatArray(16)
            // Row 0: right
            d[0] = right.x;   d[4] = right.y;   d[8] = right.z
            // Row 1: up
            d[1] = camUp.x;   d[5] = camUp.y;   d[9] = camUp.z
            // Row 2: -forward (OpenGL convention: camera looks along -Z)
            d[2] = -forward.x; d[6] = -forward.y; d[10] = -forward.z
            // Row 3: 0, 0, 0, 1
            d[15] = 1f

            // Translation: dot products for eye offset
            d[12] = -right.dot(eye)
            d[13] = -camUp.dot(eye)
            d[14] = forward.dot(eye)

            return Matrix4(d)
        }

        /**
         * Build a perspective projection matrix.
         * @param fovY Vertical field of view in radians
         * @param aspect Width/height ratio
         * @param near Near clipping plane distance
         * @param far Far clipping plane distance
         */
        fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): Matrix4 {
            val f = 1f / tan(fovY / 2f)
            val d = FloatArray(16)
            d[0] = f / aspect
            d[5] = f
            d[10] = (far + near) / (near - far)
            d[11] = -1f
            d[14] = (2f * far * near) / (near - far)
            return Matrix4(d)
        }

        /**
         * Build an orthographic projection matrix.
         * @param width View volume width
         * @param height View volume height
         * @param near Near clipping plane distance
         * @param far Far clipping plane distance
         */
        fun orthographic(width: Float, height: Float, near: Float, far: Float): Matrix4 {
            val d = FloatArray(16)
            d[0] = 2f / width
            d[5] = 2f / height
            d[10] = -2f / (far - near)
            d[14] = -(far + near) / (far - near)
            d[15] = 1f
            return Matrix4(d)
        }

        /** Rotation around the Y axis. */
        fun rotateY(radians: Float): Matrix4 {
            val c = cos(radians)
            val s = sin(radians)
            val d = FloatArray(16)
            d[0] = c;   d[8] = s
            d[5] = 1f
            d[2] = -s;  d[10] = c
            d[15] = 1f
            return Matrix4(d)
        }

        /** Rotation around the X axis. */
        fun rotateX(radians: Float): Matrix4 {
            val c = cos(radians)
            val s = sin(radians)
            val d = FloatArray(16)
            d[0] = 1f
            d[5] = c;   d[9] = -s
            d[6] = s;   d[10] = c
            d[15] = 1f
            return Matrix4(d)
        }

        /** Translation matrix. */
        fun translate(offset: Vector3): Matrix4 {
            val d = FloatArray(16)
            d[0] = 1f; d[5] = 1f; d[10] = 1f; d[15] = 1f
            d[12] = offset.x; d[13] = offset.y; d[14] = offset.z
            return Matrix4(d)
        }
    }
}
