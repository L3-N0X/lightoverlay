package de.lenox.lightoverlay

import net.minecraft.client.KeyMapping
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

    /**
     * Whether the overlay is currently shown: toggled on, or a torch is held with the torch mode enabled. A [FarmLock]
     * doesn't keep it on, so the toggle key hides the overlay and the spheres while locked.
     */
    @Volatile
    var active: Boolean = false
        private set

    /** The toggle key went down since it was last released; it toggles the overlay on release unless [comboUsed]. */
    private var togglePressed = false

    /** A combo key was pressed while the toggle key was held, so releasing it doesn't toggle the overlay. */
    private var comboUsed = false

    /**
     * Called once per client tick (start of tick) by both loaders: before vanilla handles its key mappings, so a
     * combo can swallow the clicks of vanilla mappings on the same key (F swaps hands).
     */
    fun onClientTick(mc: Minecraft) {
        val toggle = LightOverlayKeys.toggle
        while (toggle.consumeClick()) {
            if (!togglePressed) comboUsed = false
            togglePressed = true
        }
        // Counts a quick tap since the last tick too, so a combo pressed just before letting go isn't lost.
        val toggleHeld = toggle.isDown || togglePressed
        if (combo(mc, LightOverlayKeys.pinGrid, toggleHeld)) showMessage(mc, TorchPlanner.togglePin(mc))
        if (combo(mc, LightOverlayKeys.lockPosition, toggleHeld)) {
            showMessage(mc, FarmLock.toggle(mc))
            // Locking shows the overlay, so the lock is visible right away.
            if (FarmLock.locked && !LightOverlayConfig.enabled) {
                LightOverlayConfig.enabled = true
                LightOverlayConfig.save()
            }
        }
        if (togglePressed && !toggle.isDown) {
            togglePressed = false
            if (!comboUsed) {
                LightOverlayConfig.enabled = !LightOverlayConfig.enabled
                LightOverlayConfig.save()
                showMessage(
                    mc,
                    Component.translatable(
                        if (LightOverlayConfig.enabled) "lightoverlay.message.enabled" else "lightoverlay.message.disabled"
                    )
                )
            }
        }
        while (LightOverlayKeys.cycleTorchHelper.consumeClick()) showMessage(mc, TorchPlanner.cycleMode())
        while (LightOverlayKeys.openConfig.consumeClick()) {
            //? if >=26.2 {
            /*mc.gui.setScreen(LightOverlayConfigScreen.create(mc.gui.screen()))
            *///?} else {
            mc.setScreen(LightOverlayConfigScreen.create(mc.screen))
            //?}
        }

        FarmLock.tick(mc)
        val player = mc.player
        active = LightOverlayConfig.enabled ||
            (LightOverlayConfig.showWhileHoldingTorch && player != null && isHoldingTorch(player))

        LightScanner.tick(mc)
        TorchPlanner.tick(mc)
    }

    /**
     * Whether [key] was pressed as a combo with the toggle key, e.g. F7 + G. Its clicks only count while [toggleHeld]
     * (or always if the toggle key is unbound), and then the clicks of other mappings on the same key are swallowed.
     */
    private fun combo(mc: Minecraft, key: KeyMapping, toggleHeld: Boolean): Boolean {
        var clicked = false
        while (key.consumeClick()) clicked = true
        if (!clicked) return false
        if (LightOverlayKeys.toggle.isUnbound) return true
        if (!toggleHeld) return false
        comboUsed = true
        for (other in mc.options.keyMappings) {
            if (other !== key && other !== LightOverlayKeys.toggle && other.same(key)) while (other.consumeClick()) Unit
        }
        return true
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
