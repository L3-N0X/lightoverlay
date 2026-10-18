package de.lenox.lightoverlay

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

object LightOverlayKeys {
    private val categoryId: Identifier = Identifier.fromNamespaceAndPath(LightOverlayMod.MOD_ID, "main")

    // Fabric's key mapping API hooks the vanilla category registry; NeoForge wants the
    // category handed to `RegisterKeyMappingsEvent.registerCategory` instead.
    //? if fabric {
    val category: KeyMapping.Category = KeyMapping.Category.register(categoryId)
    //?}
    //? if neoforge {
    /*val category: KeyMapping.Category = KeyMapping.Category(categoryId)
    *///?}

    val toggle: KeyMapping = KeyMapping(
        "key.${LightOverlayMod.MOD_ID}.toggle",
        //? if <26.3
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_F7,
        category
    )

    val openConfig: KeyMapping = KeyMapping(
        "key.${LightOverlayMod.MOD_ID}.config",
        //? if <26.3
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.value,
        category
    )

    val cycleTorchHelper: KeyMapping = KeyMapping(
        "key.${LightOverlayMod.MOD_ID}.cycle_torch_helper",
        //? if <26.3
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.value,
        category
    )

    /** Pins the torch grid to the looked-at block. Only works while [toggle] is held (F7 + G), see [LightOverlayMod]. */
    val pinGrid: KeyMapping = KeyMapping(
        "key.${LightOverlayMod.MOD_ID}.pin_grid",
        //? if <26.3
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_G,
        category
    )

    /**
     * Locks the overlay to the player's position, see [FarmLock]. Only works while [toggle] is held (F7 + F), which
     * keeps F from also swapping hands.
     */
    val lockPosition: KeyMapping = KeyMapping(
        "key.${LightOverlayMod.MOD_ID}.lock_position",
        //? if <26.3
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_F,
        category
    )

    val all: List<KeyMapping> get() = listOf(toggle, openConfig, cycleTorchHelper, pinGrid, lockPosition)
}
