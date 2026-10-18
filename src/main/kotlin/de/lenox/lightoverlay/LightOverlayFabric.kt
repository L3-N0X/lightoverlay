//? if fabric {
package de.lenox.lightoverlay

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?}

class LightOverlayFabric : ClientModInitializer {
    override fun onInitializeClient() {
        LightOverlayConfig.init()

        //? if >=26.1 {
        /*LightOverlayKeys.all.forEach(KeyMappingHelper::registerKeyMapping)
        *///?} else {
        LightOverlayKeys.all.forEach(KeyBindingHelper::registerKeyBinding)
        //?}

        ClientTickEvents.START_CLIENT_TICK.register { mc -> LightOverlayMod.onClientTick(mc) }

        //? if >=26.2 {
        /*LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            LightOverlayRenderer.render(context.poseStack(), context.levelState().cameraRenderState.pos, context.submitNodeCollector())
        }
        *///?} elif >=26.1 {
        /*LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context ->
            LightOverlayRenderer.render(context.poseStack(), context.levelState().cameraRenderState.pos, context.bufferSource())
        }
        *///?} else {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register { context ->
            LightOverlayRenderer.render(context.matrices(), context.worldState().cameraRenderState.pos, context.consumers())
        }
        //?}
    }
}
//?}
