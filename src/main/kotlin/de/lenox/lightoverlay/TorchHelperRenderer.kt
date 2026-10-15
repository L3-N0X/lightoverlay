package de.lenox.lightoverlay

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the [TorchPlanner.Plan] in its own style, so it stands apart from the flat light level markers: translucent
 * ghost torches on the suggested spots, a pulsing beam and floor frame on the next one, and a faint tint over the
 * floor that the next torch would light up.
 */
object TorchHelperRenderer {
    /** Slightly below the light level markers, so those stay readable on tinted floor. */
    private const val PREVIEW_OFFSET = 0.005f
    private const val FRAME_OFFSET = 0.012f
    private const val PREVIEW_ALPHA = 0x38

    private const val BEAM_HEIGHT = 4f
    private const val BEAM_HALF_WIDTH = 0.12f
    private const val FRAME_INSET = 0.03f
    private const val FRAME_WIDTH = 0.06f
    private const val MARKER_HALF_SIZE = 0.12f

    /** Vanilla torch: 2/16 wide and 10/16 tall, the top 2/16 being the flame. */
    private const val TORCH_HALF_WIDTH = 1f / 16f
    private const val TORCH_HEIGHT = 10f / 16f
    private const val FLAME_HEIGHT = 2f / 16f
    private const val FLAME_COLOR = 0xFFE080

    private const val PULSE_PERIOD_MS = 1500L

    /** Vertex positions are made camera-relative here, so [pose] only needs the camera rotation. */
    fun draw(quads: VertexConsumer, pose: PoseStack.Pose, plan: TorchPlanner.Plan, cameraPos: Vec3) {
        val color = LightOverlayConfig.indicatorColor
        val phase = (System.currentTimeMillis() % PULSE_PERIOD_MS) / PULSE_PERIOD_MS.toDouble()
        val pulse = (0.7 + 0.3 * sin(phase * 2 * PI)).toFloat()

        if (LightOverlayConfig.showCoveragePreview) {
            val argb = (PREVIEW_ALPHA shl 24) or color
            for (pos in plan.preview) {
                val y = BlockPos.getY(pos)
                // The top of a floor above the camera can't be seen.
                if (y > cameraPos.y) continue
                val x = (BlockPos.getX(pos) - cameraPos.x).toFloat()
                val z = (BlockPos.getZ(pos) - cameraPos.z).toFloat()
                flatRect(quads, pose, argb, x, (y - cameraPos.y).toFloat() + PREVIEW_OFFSET, z, 0f, 0f, 1f, 1f)
            }
        }

        for (spot in plan.spots) {
            val x = (spot.x - cameraPos.x).toFloat()
            val y = (spot.y - cameraPos.y).toFloat()
            val z = (spot.z - cameraPos.z).toFloat()
            when (spot.kind) {
                TorchPlanner.SpotKind.NEXT -> {
                    val alpha = (0xD0 * pulse).toInt()
                    drawFrame(quads, pose, (alpha shl 24) or color, x, y + FRAME_OFFSET, z)
                    drawBeam(quads, pose, color, (0x90 * pulse).toInt(), x + 0.5f, y, z + 0.5f)
                    drawGhostTorch(quads, pose, color, 0xD0, x + 0.5f, y, z + 0.5f)
                }
                TorchPlanner.SpotKind.SUGGESTED -> {
                    drawFrame(quads, pose, (0x70 shl 24) or color, x, y + FRAME_OFFSET, z)
                    drawGhostTorch(quads, pose, color, 0x90, x + 0.5f, y, z + 0.5f)
                }
                TorchPlanner.SpotKind.GRID_LIT ->
                    drawDiamond(quads, pose, (0x70 shl 24) or color, x + 0.5f, y + FRAME_OFFSET, z + 0.5f)
                TorchPlanner.SpotKind.GRID_DONE ->
                    drawDiamond(quads, pose, (0xB0 shl 24) or LightOverlayConfig.safeColor, x + 0.5f, y + FRAME_OFFSET, z + 0.5f)
            }
        }
    }

    /** A torch-sized box standing on ([cx], [y], [cz]): a stick in [color] topped by a flame. */
    private fun drawGhostTorch(quads: VertexConsumer, pose: PoseStack.Pose, color: Int, alpha: Int, cx: Float, y: Float, cz: Float) {
        val w = TORCH_HALF_WIDTH
        val flameBottom = y + TORCH_HEIGHT - FLAME_HEIGHT
        box(quads, pose, (alpha shl 24) or color, cx - w, y, cz - w, cx + w, flameBottom, cz + w)
        box(quads, pose, (alpha shl 24) or FLAME_COLOR, cx - w, flameBottom, cz - w, cx + w, y + TORCH_HEIGHT, cz + w)
    }

    /** Two crossed vertical planes rising from ([cx], [y], [cz]), fading out towards the top. */
    private fun drawBeam(quads: VertexConsumer, pose: PoseStack.Pose, color: Int, alpha: Int, cx: Float, y: Float, cz: Float) {
        val bottom = (alpha shl 24) or color
        val top = color
        val w = BEAM_HALF_WIDTH
        val h = y + BEAM_HEIGHT
        quads.addVertex(pose, cx - w, y, cz).setColor(bottom)
        quads.addVertex(pose, cx + w, y, cz).setColor(bottom)
        quads.addVertex(pose, cx + w, h, cz).setColor(top)
        quads.addVertex(pose, cx - w, h, cz).setColor(top)
        quads.addVertex(pose, cx, y, cz - w).setColor(bottom)
        quads.addVertex(pose, cx, y, cz + w).setColor(bottom)
        quads.addVertex(pose, cx, h, cz + w).setColor(top)
        quads.addVertex(pose, cx, h, cz - w).setColor(top)
    }

    /** An outline along the edges of the block top whose north-west corner is ([x], [y], [z]). */
    private fun drawFrame(quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, x: Float, y: Float, z: Float) {
        val a = FRAME_INSET
        val b = FRAME_INSET + FRAME_WIDTH
        flatRect(quads, pose, argb, x, y, z, a, a, 1 - a, b)
        flatRect(quads, pose, argb, x, y, z, a, 1 - b, 1 - a, 1 - a)
        flatRect(quads, pose, argb, x, y, z, a, b, b, 1 - b)
        flatRect(quads, pose, argb, x, y, z, 1 - b, b, 1 - a, 1 - b)
    }

    /** A small flat diamond centred at ([cx], [y], [cz]). */
    private fun drawDiamond(quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, cx: Float, y: Float, cz: Float) {
        val s = MARKER_HALF_SIZE
        quads.addVertex(pose, cx - s, y, cz).setColor(argb)
        quads.addVertex(pose, cx, y, cz + s).setColor(argb)
        quads.addVertex(pose, cx + s, y, cz).setColor(argb)
        quads.addVertex(pose, cx, y, cz - s).setColor(argb)
    }

    /** A horizontal rectangle from ([x1], [z1]) to ([x2], [z2]) relative to ([x], [z]), at height [y]. */
    private fun flatRect(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, x: Float, y: Float, z: Float,
        x1: Float, z1: Float, x2: Float, z2: Float
    ) {
        quads.addVertex(pose, x + x1, y, z + z1).setColor(argb)
        quads.addVertex(pose, x + x1, y, z + z2).setColor(argb)
        quads.addVertex(pose, x + x2, y, z + z2).setColor(argb)
        quads.addVertex(pose, x + x2, y, z + z1).setColor(argb)
    }

    /** The four sides and the top of a box. Culling is off for debug quads, so the winding doesn't matter. */
    private fun box(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float
    ) {
        side(quads, pose, argb, x1, z1, x2, z1, y1, y2)
        side(quads, pose, argb, x2, z1, x2, z2, y1, y2)
        side(quads, pose, argb, x2, z2, x1, z2, y1, y2)
        side(quads, pose, argb, x1, z2, x1, z1, y1, y2)
        quads.addVertex(pose, x1, y2, z1).setColor(argb)
        quads.addVertex(pose, x1, y2, z2).setColor(argb)
        quads.addVertex(pose, x2, y2, z2).setColor(argb)
        quads.addVertex(pose, x2, y2, z1).setColor(argb)
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
