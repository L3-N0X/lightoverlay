package de.lenox.lightoverlay

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.io.File

enum class DisplayMode {
    /** The block light level, i.e. the light level at night, e.g. `7`. */
    NUMBER,

    /** The night light level, plus the (smaller) light level during the day. */
    NUMBER_DAY_NIGHT,

    /** A coloured cross on every block. */
    CROSS,

    /** A small coloured square in the middle of every block. */
    SQUARE
}

object LightOverlayConfig {
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private var configFile: File? = null

    const val DEFAULT_SAFE_COLOR = 0x40FF40
    const val DEFAULT_WARNING_COLOR = 0xFFFF40
    const val DEFAULT_DANGER_COLOR = 0xFF4040
    const val DEFAULT_SKIRT_HEIGHT = 0.5f

    var enabled: Boolean = false
    var showWhileHoldingTorch: Boolean = false
    var horizontalRadius: Int = 16
    var verticalRadius: Int = 8
    var displayMode: DisplayMode = DisplayMode.NUMBER
    var hideSafeBlocks: Boolean = false
    var updateIntervalTicks: Int = 10

    /** Draw gradient skirts on the edges of floors above the camera, whose top surface can't be seen. */
    var skirtsEnabled: Boolean = false

    /** Height of the skirts in blocks. */
    var skirtHeight: Float = DEFAULT_SKIRT_HEIGHT

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
            showWhileHoldingTorch = data.showWhileHoldingTorch
            horizontalRadius = data.horizontalRadius.coerceIn(1, 64)
            verticalRadius = data.verticalRadius.coerceIn(1, 32)
            // Unknown names (e.g. modes from older versions) deserialize to null.
            displayMode = data.displayMode ?: DisplayMode.NUMBER
            hideSafeBlocks = data.hideSafeBlocks
            updateIntervalTicks = data.updateIntervalTicks.coerceIn(1, 100)
            skirtsEnabled = data.skirtsEnabled
            skirtHeight = data.skirtHeight.coerceIn(0.1f, 1f)
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
                showWhileHoldingTorch = showWhileHoldingTorch,
                horizontalRadius = horizontalRadius,
                verticalRadius = verticalRadius,
                displayMode = displayMode,
                hideSafeBlocks = hideSafeBlocks,
                updateIntervalTicks = updateIntervalTicks,
                skirtsEnabled = skirtsEnabled,
                skirtHeight = skirtHeight,
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
        val showWhileHoldingTorch: Boolean = false,
        val horizontalRadius: Int = 16,
        val verticalRadius: Int = 8,
        val displayMode: DisplayMode? = DisplayMode.NUMBER,
        val hideSafeBlocks: Boolean = false,
        val updateIntervalTicks: Int = 10,
        val skirtsEnabled: Boolean = false,
        val skirtHeight: Float = DEFAULT_SKIRT_HEIGHT,
        val safeColor: Int = DEFAULT_SAFE_COLOR,
        val warningColor: Int = DEFAULT_WARNING_COLOR,
        val dangerColor: Int = DEFAULT_DANGER_COLOR
    )
}
