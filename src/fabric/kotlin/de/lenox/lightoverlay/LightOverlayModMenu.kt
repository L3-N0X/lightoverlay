package de.lenox.lightoverlay

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu entrypoint; only loaded when Mod Menu is installed. */
class LightOverlayModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> LightOverlayConfigScreen.create(parent) }
}
