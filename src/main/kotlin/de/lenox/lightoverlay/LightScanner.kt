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
    /**
     * @param edges bitmask of the horizontal sides (bit = [Direction.get2DDataValue]) where the floor ends,
     *   i.e. the neighbour is open and has no floor of its own. Only filled in when skirts are enabled.
     */
    class Entry(val x: Int, val y: Int, val z: Int, val blockLight: Int, val skyLight: Int, val edges: Int)

    /** Highest raw sky light at which hostile mobs can still spawn during the day. */
    private const val MAX_SPAWN_SKY_LIGHT = 7

    @Volatile
    var entries: List<Entry> = emptyList()
        private set

    private var ticksUntilScan = 0

    fun tick(mc: Minecraft) {
        val level = mc.level
        val player = mc.player
        if (!LightOverlayMod.active || level == null || player == null) {
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
        val skirts = LightOverlayConfig.skirtsEnabled

        val result = ArrayList<Entry>()
        val pos = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()
        val side = BlockPos.MutableBlockPos()

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

                    var edges = 0
                    if (skirts) {
                        for (direction in Direction.Plane.HORIZONTAL) {
                            side.setWithOffset(pos, direction)
                            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) continue
                            below.setWithOffset(side, Direction.DOWN)
                            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue
                            edges = edges or (1 shl direction.get2DDataValue())
                        }
                    }

                    result.add(Entry(pos.x, pos.y, pos.z, blockLight, skyLight, edges))
                }
            }
        }

        entries = result
    }

    /** Light level during the day: the raw sky light is only darkened at night. */
    fun dayLightOf(entry: Entry): Int = maxOf(entry.blockLight, entry.skyLight)

    /**
     * RGB colour (no alpha) for an entry, following the vanilla monster spawning rules. While [FarmLock] is locked,
     * entries outside its spawn shell can't spawn anything and get the outside colour.
     */
    fun colorOf(entry: Entry, sphere: FarmLock.Sphere? = FarmLock.sphere): Int = when {
        sphere != null && !sphere.contains(entry.x, entry.y, entry.z) -> LightOverlayConfig.outsideColor
        entry.blockLight > 0 -> LightOverlayConfig.safeColor
        entry.skyLight > MAX_SPAWN_SKY_LIGHT -> LightOverlayConfig.warningColor
        else -> LightOverlayConfig.dangerColor
    }
}
