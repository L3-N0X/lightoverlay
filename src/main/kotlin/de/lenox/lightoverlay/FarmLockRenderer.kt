package de.lenox.lightoverlay

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draws the [FarmLock] spheres as a wireframe of latitude and longitude bands, plus a marker on the locked spot. */
object FarmLockRenderer {
    private const val LATITUDE_STEP_DEG = 15
    private const val MERIDIANS = 24
    private const val SEGMENTS = 96
    private const val LINE_ALPHA = 0x90
    private const val INNER_LINE_ALPHA = 0x60

    /** Line width grows with the radius, so the far side of a big sphere stays visible. */
    private const val LINE_BASE_HALF_WIDTH = 0.05f
    private const val LINE_HALF_WIDTH_PER_BLOCK = 0.0025f

    /** The marker on the locked spot is a player-sized box. */
    private const val MARKER_HALF_WIDTH = 0.3f
    private const val MARKER_HEIGHT = 1.8f
    private const val MARKER_ALPHA = 0x50

    /** Vertex positions are made camera-relative here, so [pose] only needs the camera rotation. */
    fun draw(quads: VertexConsumer, pose: PoseStack.Pose, sphere: FarmLock.Sphere, cameraPos: Vec3) {
        val color = LightOverlayConfig.sphereColor
        val cx = (sphere.x - cameraPos.x).toFloat()
        val cy = (sphere.y - cameraPos.y).toFloat()
        val cz = (sphere.z - cameraPos.z).toFloat()

        drawSphere(quads, pose, (LINE_ALPHA shl 24) or color, cx, cy, cz, sphere.radius.toFloat())
        if (sphere.innerRadius > 0) {
            drawSphere(quads, pose, (INNER_LINE_ALPHA shl 24) or color, cx, cy, cz, sphere.innerRadius.toFloat())
        }
        drawMarker(quads, pose, (MARKER_ALPHA shl 24) or color, cx, cy, cz)
    }

    private fun drawSphere(quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, cx: Float, cy: Float, cz: Float, r: Float) {
        val w = LINE_BASE_HALF_WIDTH + r * LINE_HALF_WIDTH_PER_BLOCK
        val step = (2 * PI / SEGMENTS).toFloat()

        // Latitude bands: horizontal circles, widened along the sphere surface towards the poles.
        for (deg in -90 + LATITUDE_STEP_DEG until 90 step LATITUDE_STEP_DEG) {
            val phi = Math.toRadians(deg.toDouble()).toFloat()
            val ringRadius = r * cos(phi)
            val y = cy + r * sin(phi)
            // Offset along the meridian direction: up by cos(phi), inwards by sin(phi).
            val dy = w * cos(phi)
            val dr = -w * sin(phi)
            for (i in 0 until SEGMENTS) {
                val a0 = i * step
                val a1 = a0 + step
                val c0 = cos(a0); val s0 = sin(a0)
                val c1 = cos(a1); val s1 = sin(a1)
                val lo = ringRadius - dr
                val hi = ringRadius + dr
                quads.addVertex(pose, cx + lo * c0, y - dy, cz + lo * s0).setColor(argb)
                quads.addVertex(pose, cx + lo * c1, y - dy, cz + lo * s1).setColor(argb)
                quads.addVertex(pose, cx + hi * c1, y + dy, cz + hi * s1).setColor(argb)
                quads.addVertex(pose, cx + hi * c0, y + dy, cz + hi * s0).setColor(argb)
            }
        }

        // Meridians: half circles from pole to pole, widened sideways.
        for (m in 0 until MERIDIANS) {
            val theta = (2 * PI * m / MERIDIANS).toFloat()
            val ct = cos(theta); val st = sin(theta)
            // Sideways offset, tangent to the latitude circles.
            val ox = -st * w
            val oz = ct * w
            for (i in 0 until SEGMENTS / 2) {
                val p0 = (-PI / 2).toFloat() + i * step
                val p1 = p0 + step
                val h0 = r * cos(p0); val y0 = cy + r * sin(p0)
                val h1 = r * cos(p1); val y1 = cy + r * sin(p1)
                quads.addVertex(pose, cx + h0 * ct - ox, y0, cz + h0 * st - oz).setColor(argb)
                quads.addVertex(pose, cx + h0 * ct + ox, y0, cz + h0 * st + oz).setColor(argb)
                quads.addVertex(pose, cx + h1 * ct + ox, y1, cz + h1 * st + oz).setColor(argb)
                quads.addVertex(pose, cx + h1 * ct - ox, y1, cz + h1 * st - oz).setColor(argb)
            }
        }
    }

    /** A translucent player-sized box standing on ([cx], [y], [cz]). Culling is off for debug quads. */
    private fun drawMarker(quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, cx: Float, y: Float, cz: Float) {
        val w = MARKER_HALF_WIDTH
        val x1 = cx - w; val x2 = cx + w
        val z1 = cz - w; val z2 = cz + w
        val y2 = y + MARKER_HEIGHT
        side(quads, pose, argb, x1, z1, x2, z1, y, y2)
        side(quads, pose, argb, x2, z1, x2, z2, y, y2)
        side(quads, pose, argb, x2, z2, x1, z2, y, y2)
        side(quads, pose, argb, x1, z2, x1, z1, y, y2)
    }

    private fun side(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int,
        x1: Float, z1: Float, x2: Float, z2: Float, y1: Float, y2: Float
    ) {
        quads.addVertex(pose, x1, y1, z1).setColor(argb)
        quads.addVertex(pose, x2, y1, z2).setColor(argb)
        quads.addVertex(pose, x2, y2, z2).setColor(argb)
        quads.addVertex(pose, x1, y2, z1).setColor(argb)
    }
}
