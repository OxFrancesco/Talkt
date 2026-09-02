package org.buddytools.thinkingorbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Values mirrored from the original Thinking Orbs preset. */
data class ThinkingOrbParameters(
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val phase: Float = 0f,
    val spin: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val dots: Float = 1f,
    val spread: Float = 1f,
    val dotScale: Float = 1f,
    val perspective: Float = 1f,
    val depthSize: Float = 1f,
    val depthFade: Float = 1f,
    val opacity: Float = 1f,
)

data class OrbDot(
    val x: Float,
    val y: Float,
    val z: Float,
    val radius: Float,
    val alpha: Float,
    val accent: Float,
)

/** Pure Kotlin geometry from thinking-orbs-kotlin. Compose only paints the depth-sorted list. */
object ThinkingOrbGeometry {
    private const val BASE_LANES = 12
    private const val BASE_SEGMENTS = 44
    private const val BASE_GHOST_DOTS = 38
    private const val MIN_RADIUS = 0.3

    fun band(
        size: Float,
        timeSeconds: Double,
        parameters: ThinkingOrbParameters = ThinkingOrbParameters(),
    ): List<OrbDot> {
        require(size > 0f) { "size must be positive" }

        val direction = if (parameters.reverse) -1.0 else 1.0
        val t = (timeSeconds * parameters.speed + parameters.phase * 2.0 * PI) * direction
        val center = size / 2.0
        val radius = center * 0.78 * parameters.spread.coerceIn(0.35f, 1.45f)
        val lanes = max(2, (BASE_LANES * sqrt(parameters.dots.coerceIn(0.1f, 3f).toDouble())).roundToInt())
        val segments = max(12, (BASE_SEGMENTS * sqrt(parameters.dots.coerceIn(0.1f, 3f).toDouble())).roundToInt())
        val ghostCount = max(0, (BASE_GHOST_DOTS * parameters.dots.coerceIn(0f, 3f)).roundToInt())
        val cameraPitch = 0.30 + parameters.pitch * PI
        val cameraYaw = parameters.yaw * PI + t * 0.10 * parameters.spin
        val radiusScale = (size / 300.0).pow(0.6) * parameters.dotScale.coerceIn(0.2f, 3f)
        val output = ArrayList<OrbDot>(ghostCount + lanes * segments)

        repeat(ghostCount) { index ->
            val direction3d = fibonacciDirection(index, ghostCount)
            val projected = project(
                direction3d.x * radius,
                direction3d.y * radius,
                direction3d.z * radius,
                cameraYaw,
                cameraPitch,
                center,
                radius,
                parameters.perspective,
            )
            val depth = normalizedDepth(projected.z, radius)
            output += OrbDot(
                x = projected.x.toFloat(),
                y = projected.y.toFloat(),
                z = projected.z.toFloat(),
                radius = max(MIN_RADIUS, 0.68 * radiusScale).toFloat(),
                alpha = (parameters.opacity * depthAlpha(0.10 + 0.22 * depth, depth, parameters.depthFade)).coerceIn(0f, 1f),
                accent = 0f,
            )
        }

        val planeYaw = t * 0.24 * parameters.spin
        val planeTilt = 0.55 + 0.30 * sin(t * 0.18) * parameters.spin
        val u = Vec3(cos(planeYaw), 0.0, sin(planeYaw))
        val v = Vec3(-u.z * sin(planeTilt), cos(planeTilt), u.x * sin(planeTilt))
        val normal = u.cross(v)

        repeat(lanes) { lane ->
            val laneOffset = (lane - (lanes - 1) / 2.0) * 0.075 * parameters.spread
            val edge = abs(lane - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)

            repeat(segments) { segment ->
                val angle = segment.toDouble() / segments * 2.0 * PI
                val wobble = (
                    0.16 * sin(angle * 3.0 - t * 1.7 + lane * 0.22) +
                        0.07 * sin(angle * 5.0 + t * 1.1)
                    ) * parameters.spread
                val point = (u * cos(angle) + v * sin(angle) + normal * (laneOffset + wobble)).normalized() * radius
                val projected = project(
                    point.x,
                    point.y,
                    point.z,
                    cameraYaw,
                    cameraPitch,
                    center,
                    radius,
                    parameters.perspective,
                )
                val depth = normalizedDepth(projected.z, radius)
                val baseRadius = 0.935 + 1.445 * lerp(0.5, depth, parameters.depthSize)
                val alpha = depthAlpha(0.40 + 0.60 * depth, depth, parameters.depthFade)
                val accentCenter = fraction(t * 0.075 + lane.toDouble() / lanes)
                val angularPosition = segment.toDouble() / segments
                val accentDistance = circularDistance(angularPosition, accentCenter)
                val accent = smoothStep(0.22, 0.02, accentDistance) * (1.0 - edge * 0.45)

                output += OrbDot(
                    x = projected.x.toFloat(),
                    y = projected.y.toFloat(),
                    z = projected.z.toFloat(),
                    radius = max(MIN_RADIUS, baseRadius * (1.0 - 0.25 * edge) * radiusScale).toFloat(),
                    alpha = (parameters.opacity * alpha).coerceIn(0f, 1f),
                    accent = accent.coerceIn(0.0, 1.0).toFloat(),
                )
            }
        }

        output.sortBy(OrbDot::z)
        return output
    }

    private fun project(
        x: Double,
        y: Double,
        z: Double,
        yaw: Double,
        pitch: Double,
        center: Double,
        radius: Double,
        perspective: Float,
    ): Vec3 {
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)
        val rotatedX = x * cosYaw + z * sinYaw
        val rotatedZ = -x * sinYaw + z * cosYaw
        val sinPitch = sin(pitch)
        val cosPitch = cos(pitch)
        val rotatedY = y * cosPitch - rotatedZ * sinPitch
        val depth = y * sinPitch + rotatedZ * cosPitch
        val perspectiveAmount = parametersRange(perspective) * 0.20
        val scale = 1.0 + depth / max(1.0, radius) * perspectiveAmount
        return Vec3(center + rotatedX * scale, center - rotatedY * scale, depth)
    }

    private fun parametersRange(value: Float): Double = value.coerceIn(0f, 2f).toDouble()

    private fun depthAlpha(base: Double, depth: Double, depthFade: Float): Float {
        val amount = depthFade.coerceIn(0f, 2f).toDouble()
        return lerp(1.0, base, amount).coerceIn(0.0, 1.0).toFloat()
    }

    private fun normalizedDepth(z: Double, radius: Double): Double =
        ((z / radius + 1.0) / 2.0).coerceIn(0.0, 1.0)

    private fun fibonacciDirection(index: Int, count: Int): Vec3 {
        val golden = PI * (3.0 - sqrt(5.0))
        val y = 1.0 - 2.0 * (index + 0.5) / count
        val radial = sqrt(1.0 - y * y)
        val angle = index * golden
        return Vec3(radial * cos(angle), y, radial * sin(angle))
    }

    private fun circularDistance(a: Double, b: Double): Double {
        val direct = abs(a - b)
        return minOf(direct, 1.0 - direct)
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun fraction(value: Double): Double = value - floor(value)
    private fun lerp(start: Double, end: Double, amount: Float): Double = start + (end - start) * amount
    private fun lerp(start: Double, end: Double, amount: Double): Double = start + (end - start) * amount

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
        operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

        fun cross(other: Vec3) = Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

        fun normalized(): Vec3 {
            val length = sqrt(x * x + y * y + z * z)
            return Vec3(x / length, y / length, z / length)
        }
    }
}
