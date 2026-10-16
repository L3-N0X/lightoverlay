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

    /** Whether the overlay is currently shown: toggled on, locked with [FarmLock], or a torch is held with the torch mode enabled. */
    @Volatile
    var active: Boolean = false
        private set

    /** Called once per client tick (end of tick) by both loaders. */
    fun onClientTick(mc: Minecraft) {
        while (LightOverlayKeys.toggle.consumeClick()) {
            LightOverlayConfig.enabled = !LightOverlayConfig.enabled
            LightOverlayConfig.save()
            showMessage(
                mc,
                Component.translatable(
                    if (LightOverlayConfig.enabled) "lightoverlay.message.enabled" else "lightoverlay.message.disabled"
                )
            )
        }
        while (LightOverlayKeys.cycleTorchHelper.consumeClick()) showMessage(mc, TorchPlanner.cycleMode())
        while (LightOverlayKeys.pinGrid.consumeClick()) showMessage(mc, TorchPlanner.togglePin(mc))
        while (LightOverlayKeys.lockPosition.consumeClick()) showMessage(mc, FarmLock.toggle(mc))
        while (LightOverlayKeys.openConfig.consumeClick()) {
            //? if >=26.2 {
            /*mc.gui.setScreen(LightOverlayConfigScreen.create(mc.gui.screen()))
            *///?} else {
            mc.setScreen(LightOverlayConfigScreen.create(mc.screen))
            //?}
        }

        FarmLock.tick(mc)
        val player = mc.player
        active = LightOverlayConfig.enabled || FarmLock.locked ||
            (LightOverlayConfig.showWhileHoldingTorch && player != null && isHoldingTorch(player))

        LightScanner.tick(mc)
        TorchPlanner.tick(mc)
    }

    private fun showMessage(mc: Minecraft, message: Component) {
        //? if >=26.1 {
        /*mc.player?.sendOverlayMessage(message)
        *///?}
        //? if <26.1 {
        mc.player?.displayClientMessage(message, true)
        //?}
    }

    /** Light level of the light-emitting block held in either hand, e.g. 14 for a torch, or null if there is none. */
    fun heldLightEmission(player: Player): Int? = sequenceOf(player.mainHandItem, player.offhandItem)
        .mapNotNull { (it.item as? BlockItem)?.block?.defaultBlockState()?.lightEmission }
        .firstOrNull { it > 0 }

    private fun isHoldingTorch(player: Player): Boolean = isTorch(player.mainHandItem) || isTorch(player.offhandItem)

    /** Torches of any kind (regular, soul, copper, redstone and modded ones extending [BaseTorchBlock]). */
    private fun isTorch(stack: ItemStack): Boolean = (stack.item as? BlockItem)?.block is BaseTorchBlock
}
