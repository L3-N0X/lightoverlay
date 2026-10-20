package de.lenox.lightoverlay.mixin

import net.minecraft.client.renderer.LevelRenderer
import org.spongepowered.asm.mixin.Mixin
//? if >=26.3 {
/*import de.lenox.lightoverlay.FarmLockRenderer
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
*///?} elif >=26.2 {
/*import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import de.lenox.lightoverlay.FarmLockRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
*///?}

/**
 * From 26.2 on the loader render hooks only collect submits, so [FarmLockRenderer] can't open its own render pass
 * there. Like MaLiLib's `MixinLevelRenderer`, this hooks the level render to draw the farm lock spheres from their GPU
 * buffers. Before 26.2 it is empty.
 */
@Mixin(LevelRenderer::class)
abstract class MixinLevelRenderer {
    //? if >=26.3 {
    /*// Inside the main pass, after the translucent terrain and features, before the outlines and the always-on-top
    // features (which may clear the depth buffer).
    @Inject(method = ["executeOutline"], at = [At("HEAD")])
    private fun drawFarmLockSpheres(featureFrame: FeatureRenderDispatcher.PreparedFrame, ci: CallbackInfo) {
        FarmLockRenderer.renderLevel()
    }
    *///?} elif >=26.2 {
    /*@Shadow
    @Final
    private lateinit var targets: LevelTargetBundle

    // A frame pass of its own, right before the always-on-top pass (which clears the depth buffer).
    @Inject(method = ["addAlwaysOnTopPass"], at = [At("HEAD")])
    private fun addFarmLockPass(
        frame: FrameGraphBuilder, featureFrame: FeatureRenderDispatcher.PreparedFrame, fog: GpuBufferSlice, ci: CallbackInfo
    ) {
        FarmLockRenderer.addPass(frame, targets)
    }
    *///?}
}
