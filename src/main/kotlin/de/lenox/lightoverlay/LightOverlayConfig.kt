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

/** What the torch helper suggests. */
enum class TorchHelperMode {
    OFF,

    /** Optimised positions for the next torches, computed from the real terrain and lighting. */
    SMART,

    /** A fixed placement pattern, see [GridPattern]. */
    GRID
}

/** Torch placement patterns for flat ground. Spacing follows from the torch's light level, see [TorchPlanner]. */
enum class GridPattern {
    /** Tilted lattice whose light diamonds tile the floor without overlap: the fewest torches possible. */
    DIAMOND,

    /** Rows of torches, every other row shifted by half a gap. */
    STAGGERED,

    /** A plain square grid. Easy to follow, but needs about twice as many torches. */
    SQUARE
}

/** What the grid lines up with, unless it is pinned with the pin key. */
enum class GridAnchor {
    /** Fixed to the world origin, so the grid is the same everywhere. */
    WORLD,

    /** The torch closest to the player. */
    NEAREST_TORCH,

    /** The torch that showed up last, e.g. the one just placed: the grid follows every torch you place. */
    NEWEST_TORCH
}

object LightOverlayConfig {
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private var configFile: File? = null

    const val DEFAULT_SAFE_COLOR = 0x40FF40
    const val DEFAULT_WARNING_COLOR = 0xFFFF40
    const val DEFAULT_DANGER_COLOR = 0xFF4040
    const val DEFAULT_SKIRT_HEIGHT = 0.5f
    const val DEFAULT_INDICATOR_COLOR = 0x40D0FF

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

    var torchHelperMode: TorchHelperMode = TorchHelperMode.SMART

    /** How many torch positions [TorchHelperMode.SMART] suggests at once; the first one is the next torch. */
    var suggestionCount: Int = 3

    /** How many blocks beyond the scan radius the torch helper takes into account. */
    var lookAhead: Int = 8

    /** Light level a block needs to count as lit. 1 is enough to stop hostile mob spawns since 1.18. */
    var minLightLevel: Int = 1

    /** Tint the blocks the next torch would light up. */
    var showCoveragePreview: Boolean = true

    var gridPattern: GridPattern = GridPattern.DIAMOND
    var gridAnchor: GridAnchor = GridAnchor.NEWEST_TORCH

    /** Blocks taken off the grid spacing, to leave some slack on uneven ground. */
    var gridMargin: Int = 0

    /** RGB, no alpha. Colour of the torch helper markers. */
    var indicatorColor: Int = DEFAULT_INDICATOR_COLOR

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
            torchHelperMode = data.torchHelperMode ?: TorchHelperMode.SMART
            suggestionCount = data.suggestionCount.coerceIn(1, 8)
            lookAhead = data.lookAhead.coerceIn(0, 16)
            minLightLevel = data.minLightLevel.coerceIn(1, 15)
            showCoveragePreview = data.showCoveragePreview
            gridPattern = data.gridPattern ?: GridPattern.DIAMOND
            gridAnchor = data.gridAnchor ?: GridAnchor.NEWEST_TORCH
            gridMargin = data.gridMargin.coerceIn(0, 6)
            indicatorColor = data.indicatorColor
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
                dangerColor = dangerColor,
                torchHelperMode = torchHelperMode,
                suggestionCount = suggestionCount,
                lookAhead = lookAhead,
                minLightLevel = minLightLevel,
                showCoveragePreview = showCoveragePreview,
                gridPattern = gridPattern,
                gridAnchor = gridAnchor,
                gridMargin = gridMargin,
                indicatorColor = indicatorColor
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
        val dangerColor: Int = DEFAULT_DANGER_COLOR,
        val torchHelperMode: TorchHelperMode? = TorchHelperMode.SMART,
        val suggestionCount: Int = 3,
        val lookAhead: Int = 8,
        val minLightLevel: Int = 1,
        val showCoveragePreview: Boolean = true,
        val gridPattern: GridPattern? = GridPattern.DIAMOND,
        val gridAnchor: GridAnchor? = GridAnchor.NEWEST_TORCH,
        val gridMargin: Int = 0,
        val indicatorColor: Int = DEFAULT_INDICATOR_COLOR
    )
}
