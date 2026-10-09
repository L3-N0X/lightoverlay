package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.LightLayer

/**
 * Periodically collects the floor positions around the player that the overlay labels.
 * Runs on the client thread at the end of a tick; the renderer only reads [entries].
 */
object LightScanner {
    class Entry(val x: Int, val y: Int, val z: Int, val blockLight: Int, val skyLight: Int)

    /** Highest raw sky light at which hostile mobs can still spawn during the day. */
    private const val MAX_SPAWN_SKY_LIGHT = 7

    @Volatile
    var entries: List<Entry> = emptyList()
        private set

    private var ticksUntilScan = 0

    fun tick(mc: Minecraft) {
        val level = mc.level
        val player = mc.player
        if (!LightOverlayConfig.enabled || level == null || player == null) {
            entries = emptyList()
            ticksUntilScan = 0
            return
        }

        if (--ticksUntilScan > 0) return
        ticksUntilScan = LightOverlayConfig.updateIntervalTicks

        val center = player.blockPosition()
        val horizontal = LightOverlayConfig.horizontalRadius
        val vertical = LightOverlayConfig.verticalRadius
        val hideSafe = LightOverlayConfig.hideSafeBlocks

        val result = ArrayList<Entry>()
        val pos = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()

        for (dx in -horizontal..horizontal) {
            for (dz in -horizontal..horizontal) {
                for (dy in -vertical..vertical) {
                    pos.set(center.x + dx, center.y + dy, center.z + dz)

                    val state = level.getBlockState(pos)
                    if (!state.getCollisionShape(level, pos).isEmpty() || !state.fluidState.isEmpty()) continue

                    below.setWithOffset(pos, Direction.DOWN)
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue

                    val blockLight = level.getBrightness(LightLayer.BLOCK, pos)
                    if (hideSafe && blockLight > 0) continue
                    val skyLight = level.getBrightness(LightLayer.SKY, pos)

                    result.add(Entry(pos.x, pos.y, pos.z, blockLight, skyLight))
                }
            }
        }

        entries = result
    }

    /** RGB colour (no alpha) for an entry, following the vanilla monster spawning rules. */
    fun colorOf(entry: Entry): Int = when {
        entry.blockLight > 0 -> LightOverlayConfig.safeColor
        entry.skyLight > MAX_SPAWN_SKY_LIGHT -> LightOverlayConfig.warningColor
        else -> LightOverlayConfig.dangerColor
    }
}
