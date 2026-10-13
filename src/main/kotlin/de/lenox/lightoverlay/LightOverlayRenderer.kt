package de.lenox.lightoverlay

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.network.chat.Component
*///?} else {
import net.minecraft.client.renderer.MultiBufferSource
//?}
//? if <26.2 {
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.Direction
//?}
import net.minecraft.world.phys.Vec3

/** Draws the light level indicators flat on top of the scanned floor blocks. */
object LightOverlayRenderer {
    private const val FULL_BRIGHT = 0xF000F0
    private const val TEXT_SCALE = 1f / 32f
    private const val SMALL_TEXT_SCALE = 1f / 64f
    private const val FLOOR_OFFSET = 0.01

    /** Offset of the day light number from the block centre, towards the +X/+Z corner. */
    private const val DAY_NUMBER_OFFSET = 0.3f

    private const val CROSS_INSET = 0.2f
    private const val CROSS_HALF_WIDTH = 0.04f
    private const val SQUARE_HALF_SIZE = 0.15f
    private const val SHAPE_ALPHA = 0xD0
    private const val SKIRT_ALPHA = 0xA0

    /**
     * @param poseStack the world pose stack of the current frame (camera rotation applied, not translation)
     * @param cameraPos camera position used to make vertex coordinates camera-relative
     * @param buffers where to draw: a buffer source the caller flushes (before 26.2), or the level's submit node collector
     */
    //? if >=26.2 {
    /*fun render(poseStack: PoseStack, cameraPos: Vec3, buffers: SubmitNodeCollector) {
    *///?} else {
    fun render(poseStack: PoseStack, cameraPos: Vec3, buffers: MultiBufferSource) {
    //?}
        if (!LightOverlayMod.active) return
        val entries = LightScanner.entries
        if (entries.isEmpty()) return

        val font = Minecraft.getInstance().font
        val mode = LightOverlayConfig.displayMode
        //? if <26.2 {
        val skirts = LightOverlayConfig.skirtsEnabled
        val skirtHeight = LightOverlayConfig.skirtHeight
        val quads = if (mode == DisplayMode.CROSS || mode == DisplayMode.SQUARE || skirts) {
            buffers.getBuffer(RenderTypes.debugQuads())
        } else {
            null
        }
        //?}

        for (entry in entries) {
            val color = LightScanner.colorOf(entry)

            poseStack.pushPose()
            poseStack.translate(entry.x - cameraPos.x, entry.y - cameraPos.y, entry.z - cameraPos.z)

            // The top of a floor above the camera can't be seen, so mark its open edges instead.
            if (entry.y > cameraPos.y) {
                //? if <26.2 {
                if (skirts && entry.edges != 0) drawSkirts(quads!!, poseStack.last(), entry.edges, skirtHeight, color)
                //?}
                poseStack.popPose()
                continue
            }

            poseStack.translate(0.5, FLOOR_OFFSET, 0.5)
            when (mode) {
                DisplayMode.NUMBER -> drawNumber(font, poseStack, buffers, entry.blockLight, color, TEXT_SCALE)
                DisplayMode.NUMBER_DAY_NIGHT -> {
                    drawNumber(font, poseStack, buffers, entry.blockLight, color, TEXT_SCALE)
                    poseStack.translate(DAY_NUMBER_OFFSET, 0f, DAY_NUMBER_OFFSET)
                    drawNumber(font, poseStack, buffers, LightScanner.dayLightOf(entry), color, SMALL_TEXT_SCALE)
                }
                //? if <26.2 {
                DisplayMode.CROSS -> drawCross(quads!!, poseStack.last(), color)
                DisplayMode.SQUARE -> drawSquare(quads!!, poseStack.last(), color)
                //?}
                //? if >=26.2 {
                /*else -> Unit
                *///?}
            }
            poseStack.popPose()
        }
    }

    /** Draws [value] centred at the current origin, flat on the floor and readable from above. */
    //? if >=26.2 {
    /*private fun drawNumber(font: Font, poseStack: PoseStack, buffers: SubmitNodeCollector, value: Int, color: Int, scale: Float) {
    *///?} else {
    private fun drawNumber(font: Font, poseStack: PoseStack, buffers: MultiBufferSource, value: Int, color: Int, scale: Float) {
    //?}
        val text = value.toString()
        poseStack.pushPose()
        // Lay the text flat on the floor, readable from above.
        //? if >=26.3 {
        /*poseStack.rotateDegrees(Axis.XP, 90f)
        *///?} else {
        poseStack.mulPose(Axis.XP.rotationDegrees(90f))
        //?}
        poseStack.scale(scale, scale, scale)
        //? if >=26.2 {
        /*buffers.submitText(
            poseStack,
            -font.width(text) / 2f,
            -font.lineHeight / 2f,
            Component.literal(text).visualOrderText,
            false,
            Font.DisplayMode.POLYGON_OFFSET,
            FULL_BRIGHT,
            0xFF000000.toInt() or color,
            0,
            0
        )
        *///?} else {
        font.drawInBatch(
            text,
            -font.width(text) / 2f,
            -font.lineHeight / 2f,
            0xFF000000.toInt() or color,
            false,
            poseStack.last().pose(),
            buffers,
            Font.DisplayMode.POLYGON_OFFSET,
            0,
            FULL_BRIGHT
        )
        //?}
        poseStack.popPose()
    }

    //? if <26.2 {
    /** Two diagonal bars from corner to corner, centred at the current origin. */
    private fun drawCross(quads: VertexConsumer, pose: PoseStack.Pose, color: Int) {
        val argb = (SHAPE_ALPHA shl 24) or color
        val far = 0.5f - CROSS_INSET
        // Offset perpendicular to a diagonal bar, so the bar is 2 * CROSS_HALF_WIDTH wide.
        val w = CROSS_HALF_WIDTH * 0.70710677f
        flatQuad(quads, pose, argb, -far - w, -far + w, -far + w, -far - w, far + w, far - w, far - w, far + w)
        flatQuad(quads, pose, argb, -far - w, far - w, -far + w, far + w, far + w, -far + w, far - w, -far - w)
    }

    private fun drawSquare(quads: VertexConsumer, pose: PoseStack.Pose, color: Int) {
        val argb = (SHAPE_ALPHA shl 24) or color
        val s = SQUARE_HALF_SIZE
        flatQuad(quads, pose, argb, -s, -s, -s, s, s, s, s, -s)
    }

    /** A horizontal quad at the current origin's height, given as four (x, z) corners. */
    private fun flatQuad(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int,
        x1: Float, z1: Float, x2: Float, z2: Float, x3: Float, z3: Float, x4: Float, z4: Float
    ) {
        quads.addVertex(pose, x1, 0f, z1).setColor(argb)
        quads.addVertex(pose, x2, 0f, z2).setColor(argb)
        quads.addVertex(pose, x3, 0f, z3).setColor(argb)
        quads.addVertex(pose, x4, 0f, z4).setColor(argb)
    }

    /**
     * Vertical quads standing on the open [edges] of the floor block whose top is at the current origin,
     * fading out towards [height].
     */
    private fun drawSkirts(quads: VertexConsumer, pose: PoseStack.Pose, edges: Int, height: Float, color: Int) {
        val bottom = (SKIRT_ALPHA shl 24) or color
        for (direction in Direction.Plane.HORIZONTAL) {
            if (edges and (1 shl direction.get2DDataValue()) == 0) continue
            // Corners of the block's top face along this edge, in counter-clockwise order.
            val (x1, z1, x2, z2) = when (direction) {
                Direction.NORTH -> floatArrayOf(0f, 0f, 1f, 0f)
                Direction.SOUTH -> floatArrayOf(1f, 1f, 0f, 1f)
                Direction.WEST -> floatArrayOf(0f, 1f, 0f, 0f)
                else -> floatArrayOf(1f, 0f, 1f, 1f)
            }
            quads.addVertex(pose, x1, 0f, z1).setColor(bottom)
            quads.addVertex(pose, x2, 0f, z2).setColor(bottom)
            quads.addVertex(pose, x2, height, z2).setColor(color)
            quads.addVertex(pose, x1, height, z1).setColor(color)
        }
    }
    //?}
}
