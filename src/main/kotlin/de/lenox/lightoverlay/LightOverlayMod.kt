package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BaseTorchBlock
import org.slf4j.LoggerFactory

object LightOverlayMod {
    const val MOD_ID = "lightoverlay"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** Whether the overlay is currently shown: toggled on, or a torch is held with the torch mode enabled. */
    @Volatile
    var active: Boolean = false
        private set

    /** Called once per client tick (end of tick) by both loaders. */
    fun onClientTick(mc: Minecraft) {
        while (LightOverlayKeys.toggle.consumeClick()) {
            LightOverlayConfig.enabled = !LightOverlayConfig.enabled
            LightOverlayConfig.save()
            val message = Component.translatable(
                if (LightOverlayConfig.enabled) "lightoverlay.message.enabled" else "lightoverlay.message.disabled"
            )
            //? if >=26.1 {
            /*mc.player?.sendOverlayMessage(message)
            *///?}
            //? if <26.1 {
            mc.player?.displayClientMessage(message, true)
            //?}
        }
        while (LightOverlayKeys.openConfig.consumeClick()) {
            //? if >=26.2 {
            /*mc.gui.setScreen(LightOverlayConfigScreen.create(mc.gui.screen()))
            *///?} else {
            mc.setScreen(LightOverlayConfigScreen.create(mc.screen))
            //?}
        }

        val player = mc.player
        active = LightOverlayConfig.enabled ||
            (LightOverlayConfig.showWhileHoldingTorch && player != null && isHoldingTorch(player))

        LightScanner.tick(mc)
    }

    private fun isHoldingTorch(player: Player): Boolean = isTorch(player.mainHandItem) || isTorch(player.offhandItem)

    /** Torches of any kind (regular, soul, copper, redstone and modded ones extending [BaseTorchBlock]). */
    private fun isTorch(stack: ItemStack): Boolean = (stack.item as? BlockItem)?.block is BaseTorchBlock
}
