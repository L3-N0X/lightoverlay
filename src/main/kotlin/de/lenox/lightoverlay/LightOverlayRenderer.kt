package de.lenox.lightoverlay

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3

/** Draws the light level numbers flat on top of the scanned floor blocks. */
object LightOverlayRenderer {
    private const val FULL_BRIGHT = 0xF000F0
    private const val TEXT_SCALE = 1f / 32f
    private const val FLOOR_OFFSET = 0.01

    /**
     * @param poseStack the world pose stack of the current frame (camera rotation applied, not translation)
     * @param cameraPos camera position used to make vertex coordinates camera-relative
     * @param buffers buffer source to submit into; the caller is responsible for flushing it
     */
    fun render(poseStack: PoseStack, cameraPos: Vec3, buffers: MultiBufferSource) {
        if (!LightOverlayConfig.enabled) return
        val entries = LightScanner.entries
        if (entries.isEmpty()) return

        val font = Minecraft.getInstance().font
        val showSky = LightOverlayConfig.displayMode == DisplayMode.BLOCK_AND_SKY

        for (entry in entries) {
            val text = if (showSky) "${entry.blockLight}/${entry.skyLight}" else entry.blockLight.toString()
            val color = 0xFF000000.toInt() or LightScanner.colorOf(entry)

            poseStack.pushPose()
            poseStack.translate(
                entry.x + 0.5 - cameraPos.x,
                entry.y + FLOOR_OFFSET - cameraPos.y,
                entry.z + 0.5 - cameraPos.z
            )
            // Lay the text flat on the floor, readable from above.
            poseStack.mulPose(Axis.XP.rotationDegrees(90f))
            poseStack.scale(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE)

            font.drawInBatch(
                text,
                -font.width(text) / 2f,
                -font.lineHeight / 2f,
                color,
                false,
                poseStack.last().pose(),
                buffers,
                Font.DisplayMode.POLYGON_OFFSET,
                0,
                FULL_BRIGHT
            )
            poseStack.popPose()
        }
    }
}
