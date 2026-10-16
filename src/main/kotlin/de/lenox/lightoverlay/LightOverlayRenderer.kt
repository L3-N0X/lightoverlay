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
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/** Draws the light level indicators flat on top of the scanned floor blocks. */
object LightOverlayRenderer {
    private const val FULL_BRIGHT = 0xF000F0
    private const val TEXT_SCALE = 1f / 32f
    private const val SMALL_TEXT_SCALE = 1f / 64f
    private const val FLOOR_OFFSET = 0.01f

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
        val plan = TorchPlanner.plan
        val sphere = FarmLock.sphere?.takeIf { LightOverlayConfig.showLockSphere }
        if (entries.isEmpty() && plan.isEmpty && sphere == null) return

        val mode = LightOverlayConfig.displayMode
        val skirts = LightOverlayConfig.skirtsEnabled
        val shapes = mode == DisplayMode.CROSS || mode == DisplayMode.SQUARE || skirts
        if (shapes || !plan.isEmpty || sphere != null) {
            val skirtHeight = LightOverlayConfig.skirtHeight
            //? if >=26.2 {
            /*buffers.submitCustomGeometry(poseStack, RenderTypes.debugQuads()) { pose, quads ->
                if (shapes) drawQuads(quads, pose, entries, cameraPos, mode, skirts, skirtHeight)
                TorchHelperRenderer.draw(quads, pose, plan, cameraPos)
                if (sphere != null) FarmLockRenderer.draw(quads, pose, sphere, cameraPos)
            }
            *///?} else {
            val quads = buffers.getBuffer(RenderTypes.debugQuads())
            if (shapes) drawQuads(quads, poseStack.last(), entries, cameraPos, mode, skirts, skirtHeight)
            TorchHelperRenderer.draw(quads, poseStack.last(), plan, cameraPos)
            if (sphere != null) FarmLockRenderer.draw(quads, poseStack.last(), sphere, cameraPos)
            //?}
        }
        if (mode != DisplayMode.NUMBER && mode != DisplayMode.NUMBER_DAY_NIGHT) return

        val font = Minecraft.getInstance().font
        for (entry in entries) {
            // The top of a floor above the camera can't be seen.
            if (entry.y > cameraPos.y) continue
            val color = LightScanner.colorOf(entry)

            poseStack.pushPose()
            poseStack.translate(entry.x - cameraPos.x + 0.5, entry.y - cameraPos.y + FLOOR_OFFSET, entry.z - cameraPos.z + 0.5)
            drawNumber(font, poseStack, buffers, entry.blockLight, color, TEXT_SCALE)
            if (mode == DisplayMode.NUMBER_DAY_NIGHT) {
                poseStack.translate(DAY_NUMBER_OFFSET, 0f, DAY_NUMBER_OFFSET)
                drawNumber(font, poseStack, buffers, LightScanner.dayLightOf(entry), color, SMALL_TEXT_SCALE)
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

    /**
     * Draws the crosses or squares on visible floors and the skirts on floors above the camera, all into one quad
     * buffer. Vertex positions are made camera-relative here, so [pose] only needs the camera rotation.
     */
    private fun drawQuads(
        quads: VertexConsumer, pose: PoseStack.Pose, entries: List<LightScanner.Entry>, cameraPos: Vec3,
        mode: DisplayMode, skirts: Boolean, skirtHeight: Float
    ) {
        for (entry in entries) {
            val color = LightScanner.colorOf(entry)
            val x = (entry.x - cameraPos.x).toFloat()
            val y = (entry.y - cameraPos.y).toFloat()
            val z = (entry.z - cameraPos.z).toFloat()

            // The top of a floor above the camera can't be seen, so mark its open edges instead.
            if (entry.y > cameraPos.y) {
                if (skirts && entry.edges != 0) drawSkirts(quads, pose, x, y, z, entry.edges, skirtHeight, color)
                continue
            }

            when (mode) {
                DisplayMode.CROSS -> drawCross(quads, pose, x + 0.5f, y + FLOOR_OFFSET, z + 0.5f, color)
                DisplayMode.SQUARE -> drawSquare(quads, pose, x + 0.5f, y + FLOOR_OFFSET, z + 0.5f, color)
                else -> Unit
            }
        }
    }

    /** Two diagonal bars from corner to corner, centred at ([cx], [cy], [cz]). */
    private fun drawCross(quads: VertexConsumer, pose: PoseStack.Pose, cx: Float, cy: Float, cz: Float, color: Int) {
        val argb = (SHAPE_ALPHA shl 24) or color
        val far = 0.5f - CROSS_INSET
        // Offset perpendicular to a diagonal bar, so the bar is 2 * CROSS_HALF_WIDTH wide.
        val w = CROSS_HALF_WIDTH * 0.70710677f
        flatQuad(quads, pose, argb, cx, cy, cz, -far - w, -far + w, -far + w, -far - w, far + w, far - w, far - w, far + w)
        flatQuad(quads, pose, argb, cx, cy, cz, -far - w, far - w, -far + w, far + w, far + w, -far + w, far - w, -far - w)
    }

    /** A square centred at ([cx], [cy], [cz]). */
    private fun drawSquare(quads: VertexConsumer, pose: PoseStack.Pose, cx: Float, cy: Float, cz: Float, color: Int) {
        val argb = (SHAPE_ALPHA shl 24) or color
        val s = SQUARE_HALF_SIZE
        flatQuad(quads, pose, argb, cx, cy, cz, -s, -s, -s, s, s, s, s, -s)
    }

    /** A horizontal quad at height [cy], given as four (x, z) corners relative to ([cx], [cz]). */
    private fun flatQuad(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, cx: Float, cy: Float, cz: Float,
        x1: Float, z1: Float, x2: Float, z2: Float, x3: Float, z3: Float, x4: Float, z4: Float
    ) {
        quads.addVertex(pose, cx + x1, cy, cz + z1).setColor(argb)
        quads.addVertex(pose, cx + x2, cy, cz + z2).setColor(argb)
        quads.addVertex(pose, cx + x3, cy, cz + z3).setColor(argb)
        quads.addVertex(pose, cx + x4, cy, cz + z4).setColor(argb)
    }

    /**
     * Vertical quads standing on the open [edges] of the floor block whose top face has its north-west corner at
     * ([x], [y], [z]), fading out towards [height].
     */
    private fun drawSkirts(
        quads: VertexConsumer, pose: PoseStack.Pose, x: Float, y: Float, z: Float, edges: Int, height: Float, color: Int
    ) {
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
            quads.addVertex(pose, x + x1, y, z + z1).setColor(bottom)
            quads.addVertex(pose, x + x2, y, z + z2).setColor(bottom)
            quads.addVertex(pose, x + x2, y + height, z + z2).setColor(color)
            quads.addVertex(pose, x + x1, y + height, z + z1).setColor(color)
        }
    }
}
