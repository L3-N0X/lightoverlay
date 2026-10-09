package de.lenox.lightoverlay

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
*///?}
//? if <26.1 {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?}

class LightOverlayFabric : ClientModInitializer {
    override fun onInitializeClient() {
        LightOverlayConfig.init()

        //? if >=26.1 {
        /*LightOverlayKeys.all.forEach(KeyMappingHelper::registerKeyMapping)
        *///?}
        //? if <26.1 {
        LightOverlayKeys.all.forEach(KeyBindingHelper::registerKeyBinding)
        //?}

        ClientTickEvents.END_CLIENT_TICK.register { mc -> LightOverlayMod.onClientTick(mc) }

        //? if >=26.1 {
        /*LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context ->
            LightOverlayRenderer.render(context.poseStack(), context.levelState().cameraRenderState.pos, context.bufferSource())
        }
        *///?}
        //? if <26.1 {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            LightOverlayRenderer.render(context.matrices(), context.worldState().cameraRenderState.pos, context.consumers())
        }
        //?}
    }
}
