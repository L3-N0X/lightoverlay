package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object LightOverlayMod {
    const val MOD_ID = "lightoverlay"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** Called once per client tick (end of tick) by both loaders. */
    fun onClientTick(mc: Minecraft) {
        while (LightOverlayKeys.toggle.consumeClick()) {
            LightOverlayConfig.enabled = !LightOverlayConfig.enabled
            LightOverlayConfig.save()
            mc.player?.displayClientMessage(
                Component.translatable(
                    if (LightOverlayConfig.enabled) "lightoverlay.message.enabled" else "lightoverlay.message.disabled"
                ),
                true
            )
        }
        while (LightOverlayKeys.openConfig.consumeClick()) {
            mc.setScreen(LightOverlayConfigScreen.create(mc.screen))
        }

        LightScanner.tick(mc)
    }
}
