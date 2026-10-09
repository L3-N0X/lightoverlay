package de.lenox.lightoverlay

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.io.File

enum class DisplayMode {
    /** Only the block light level, e.g. `7`. */
    BLOCK_LIGHT,

    /** Block and sky light, e.g. `7/15`. */
    BLOCK_AND_SKY
}

object LightOverlayConfig {
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private var configFile: File? = null

    const val DEFAULT_SAFE_COLOR = 0x40FF40
    const val DEFAULT_WARNING_COLOR = 0xFFFF40
    const val DEFAULT_DANGER_COLOR = 0xFF4040

    var enabled: Boolean = false
    var horizontalRadius: Int = 16
    var verticalRadius: Int = 8
    var displayMode: DisplayMode = DisplayMode.BLOCK_LIGHT
    var hideSafeBlocks: Boolean = false
    var updateIntervalTicks: Int = 10

    /** RGB, no alpha. Block light > 0: mobs can't spawn. */
    var safeColor: Int = DEFAULT_SAFE_COLOR

    /** RGB, no alpha. Block light 0 but bright sky: mobs spawn at night only. */
    var warningColor: Int = DEFAULT_WARNING_COLOR

    /** RGB, no alpha. Block light 0 and dark sky: mobs can always spawn. */
    var dangerColor: Int = DEFAULT_DANGER_COLOR

    fun init() {
        val mc = Minecraft.getInstance()
        configFile = File(mc.gameDirectory, "config/${LightOverlayMod.MOD_ID}.json")
        load()
    }

    private fun load() {
        val file = configFile ?: return
        if (!file.exists()) {
            save()
            return
        }
        try {
            val data = GSON.fromJson(file.readText(), ConfigData::class.java) ?: return
            enabled = data.enabled
            horizontalRadius = data.horizontalRadius.coerceIn(1, 64)
            verticalRadius = data.verticalRadius.coerceIn(1, 32)
            displayMode = data.displayMode ?: DisplayMode.BLOCK_LIGHT
            hideSafeBlocks = data.hideSafeBlocks
            updateIntervalTicks = data.updateIntervalTicks.coerceIn(1, 100)
            safeColor = data.safeColor
            warningColor = data.warningColor
            dangerColor = data.dangerColor
        } catch (e: Exception) {
            LightOverlayMod.LOGGER.warn("Failed to load light overlay config, using defaults", e)
        }
    }

    fun save() {
        val file = configFile ?: return
        try {
            file.parentFile?.mkdirs()
            val data = ConfigData(
                enabled = enabled,
                horizontalRadius = horizontalRadius,
                verticalRadius = verticalRadius,
                displayMode = displayMode,
                hideSafeBlocks = hideSafeBlocks,
                updateIntervalTicks = updateIntervalTicks,
                safeColor = safeColor,
                warningColor = warningColor,
                dangerColor = dangerColor
            )
            file.writeText(GSON.toJson(data))
        } catch (e: Exception) {
            LightOverlayMod.LOGGER.error("Failed to save light overlay config", e)
        }
    }

    private data class ConfigData(
        val enabled: Boolean = false,
        val horizontalRadius: Int = 16,
        val verticalRadius: Int = 8,
        val displayMode: DisplayMode? = DisplayMode.BLOCK_LIGHT,
        val hideSafeBlocks: Boolean = false,
        val updateIntervalTicks: Int = 10,
        val safeColor: Int = DEFAULT_SAFE_COLOR,
        val warningColor: Int = DEFAULT_WARNING_COLOR,
        val dangerColor: Int = DEFAULT_DANGER_COLOR
    )
}
