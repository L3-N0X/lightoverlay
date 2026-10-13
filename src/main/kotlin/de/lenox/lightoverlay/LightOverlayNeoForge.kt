//? if neoforge {
/*package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
//? if >=26.2 {
/*import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
*///?} else {
import com.mojang.blaze3d.vertex.PoseStack
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
//?}
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

@Mod(LightOverlayMod.MOD_ID, dist = [Dist.CLIENT])
class LightOverlayNeoForge(container: ModContainer) {
    init {
        container.registerExtensionPoint(
            IConfigScreenFactory::class.java,
            IConfigScreenFactory { _, parent -> LightOverlayConfigScreen.create(parent) }
        )
    }
}

@EventBusSubscriber(modid = LightOverlayMod.MOD_ID, value = [Dist.CLIENT])
object LightOverlayNeoForgeEvents {
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        LightOverlayConfig.init()
    }

    @SubscribeEvent
    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.registerCategory(LightOverlayKeys.category)
        LightOverlayKeys.all.forEach(event::register)
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        LightOverlayMod.onClientTick(Minecraft.getInstance())
    }

    //? if >=26.2 {
    /*@SubscribeEvent
    fun onSubmitGeometry(event: SubmitCustomGeometryEvent) {
        LightOverlayRenderer.render(
            event.poseStack,
            event.levelRenderState.cameraRenderState.pos,
            event.submitNodeCollector
        )
    }
    *///?} else {
    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent.AfterTranslucentBlocks) {
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        LightOverlayRenderer.render(
            event.poseStack,
            event.levelRenderState.cameraRenderState.pos,
            buffers
        )
        buffers.endBatch()
    }
    //?}
}
*///?}
