package de.lenox.lightoverlay

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.BaseTorchBlock

/**
 * A copy of the blocks in a box that matter for lighting: how much each cell dampens light, which cells are floors
 * (open cells on a sturdy top face, where mobs spawn and torches can be placed) with their block light, and where
 * torches stand. Taken on the client thread, so [TorchPlanner] can work on it in the background.
 *
 * Cells are addressed by an index; [index] and [xOf]/[yOf]/[zOf] convert between it and block coordinates.
 */
class LightSnapshot private constructor(
    val minX: Int, val minY: Int, val minZ: Int,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int
) {
    private val strideX = sizeY * sizeZ
    val cellCount = sizeX * strideX

    /** Light dampening per cell: 0 for air, 15 for opaque blocks. */
    private val dampening = ByteArray(cellCount)

    /** Block light of floor cells, -1 for every other cell. */
    private val floorLight = ByteArray(cellCount).also { it.fill(-1) }

    /** Positions ([BlockPos.asLong]) of all torches in the box. */
    var torches: LongArray = LongArray(0)
        private set

    val maxX get() = minX + sizeX - 1
    val maxY get() = minY + sizeY - 1
    val maxZ get() = minZ + sizeZ - 1

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun index(x: Int, y: Int, z: Int): Int = (x - minX) * strideX + (z - minZ) * sizeY + (y - minY)

    fun xOf(cell: Int): Int = minX + cell / strideX
    fun zOf(cell: Int): Int = minZ + cell % strideX / sizeY
    fun yOf(cell: Int): Int = minY + cell % sizeY

    fun isFloor(cell: Int): Boolean = floorLight[cell] >= 0

    /** Block light of a floor cell. */
    fun blockLight(cell: Int): Int = floorLight[cell].toInt()

    /** Whether [other] covers the same box with the same blocks and light. */
    fun sameAs(other: LightSnapshot?): Boolean =
        other != null && minX == other.minX && minY == other.minY && minZ == other.minZ &&
            sizeX == other.sizeX && sizeY == other.sizeY && sizeZ == other.sizeZ &&
            dampening.contentEquals(other.dampening) && floorLight.contentEquals(other.floorLight)

    /** The floor cell in column ([x], [z]) between [fromY] and [toY] closest to [y], preferring lower ones; -1 if none. */
    fun floorNear(x: Int, z: Int, y: Int, fromY: Int, toY: Int): Int {
        if (x !in minX..maxX || z !in minZ..maxZ) return -1
        val low = maxOf(fromY, minY)
        val high = minOf(toY, maxY)
        for (d in 0..maxOf(y - low, high - y)) {
            if (y - d in low..high && isFloor(index(x, y - d, z))) return index(x, y - d, z)
            if (d > 0 && y + d in low..high && isFloor(index(x, y + d, z))) return index(x, y + d, z)
        }
        return -1
    }

    /**
     * Steps from [start] to every cell, moving only through cells that let light through (so not through walls),
     * found with a breadth-first search. [UNREACHABLE] for cells that can't be reached inside the box.
     */
    fun distancesFrom(start: Int): IntArray {
        val distance = IntArray(cellCount) { UNREACHABLE }
        val queue = IntArray(cellCount)
        var head = 0
        var tail = 0
        distance[start] = 0
        queue[tail++] = start
        while (head < tail) {
            val cell = queue[head++]
            val next = distance[cell] + 1
            fun visit(neighbour: Int) {
                if (distance[neighbour] == UNREACHABLE && dampening[neighbour] < OPAQUE) {
                    distance[neighbour] = next
                    queue[tail++] = neighbour
                }
            }
            val y = cell % sizeY
            val z = cell % strideX / sizeY
            val x = cell / strideX
            if (x > 0) visit(cell - strideX)
            if (x < sizeX - 1) visit(cell + strideX)
            if (z > 0) visit(cell - sizeY)
            if (z < sizeZ - 1) visit(cell + sizeY)
            if (y > 0) visit(cell - 1)
            if (y < sizeY - 1) visit(cell + 1)
        }
        return distance
    }

    /**
     * Spreads light from cells, the way the vanilla light engine does: every step loses
     * `max(1, dampening)` levels, so opaque blocks stop it. One spreader per thread, it reuses its buffers.
     */
    inner class Spreader {
        private val level = ByteArray(cellCount)
        private var touched = IntArray(1024)
        private var touchedCount = 0
        private val buckets = Array(16) { IntArray(256) }
        private val bucketSizes = IntArray(16)

        /**
         * Spreads [emission] from [start] and calls [visit] once for every cell that ends up with at least
         * [minLight], with its light level.
         */
        fun spread(start: Int, emission: Int, minLight: Int, visit: (cell: Int, light: Int) -> Unit) {
            for (i in 0 until touchedCount) level[touched[i]] = 0
            touchedCount = 0

            set(start, emission)
            for (light in emission downTo maxOf(minLight, 1)) {
                val bucket = buckets[light]
                // Neighbours only ever go into lower buckets, so this one doesn't grow while it is walked.
                for (k in 0 until bucketSizes[light]) {
                    val cell = bucket[k]
                    if (level[cell].toInt() != light) continue
                    visit(cell, light)

                    val y = cell % sizeY
                    val z = cell % strideX / sizeY
                    val x = cell / strideX
                    if (x > 0) relax(cell - strideX, light, minLight)
                    if (x < sizeX - 1) relax(cell + strideX, light, minLight)
                    if (z > 0) relax(cell - sizeY, light, minLight)
                    if (z < sizeZ - 1) relax(cell + sizeY, light, minLight)
                    if (y > 0) relax(cell - 1, light, minLight)
                    if (y < sizeY - 1) relax(cell + 1, light, minLight)
                }
                bucketSizes[light] = 0
            }
        }

        private fun relax(cell: Int, from: Int, minLight: Int) {
            val light = from - maxOf(1, dampening[cell].toInt())
            if (light < minLight || light <= level[cell]) return
            set(cell, light)
        }

        private fun set(cell: Int, light: Int) {
            if (level[cell].toInt() == 0) {
                if (touchedCount == touched.size) touched = touched.copyOf(touched.size * 2)
                touched[touchedCount++] = cell
            }
            level[cell] = light.toByte()
            val size = bucketSizes[light]
            if (size == buckets[light].size) buckets[light] = buckets[light].copyOf(size * 2)
            buckets[light][size] = cell
            bucketSizes[light] = size + 1
        }
    }

    companion object {
        const val UNREACHABLE = Int.MAX_VALUE
        private const val OPAQUE: Byte = 15

        /** Copies the box from [minX], [minY], [minZ] to [maxX], [maxY], [maxZ] (inclusive), clamped to the build height. */
        fun take(level: Level, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int): LightSnapshot {
            val bottom = maxOf(minY, level.minY)
            val top = maxOf(bottom, minOf(maxY, level.maxY))
            val snapshot = LightSnapshot(minX, bottom, minZ, maxX - minX + 1, top - bottom + 1, maxZ - minZ + 1)
            snapshot.fill(level)
            return snapshot
        }
    }

    private fun fill(level: Level) {
        val pos = BlockPos.MutableBlockPos()
        val torchList = ArrayList<Long>()
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val chunk = level.getChunk(x shr 4, z shr 4)
                pos.set(x, minY - 1, z)
                var floorBelow = chunk.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)
                for (y in minY..maxY) {
                    pos.set(x, y, z)
                    val state = chunk.getBlockState(pos)
                    val cell = index(x, y, z)
                    //? if >=26.1 {
                    /*dampening[cell] = state.lightDampening.toByte()
                    *///?} else {
                    dampening[cell] = state.lightBlock.toByte()
                    //?}
                    if (floorBelow && state.getCollisionShape(level, pos).isEmpty && state.fluidState.isEmpty) {
                        floorLight[cell] = level.getBrightness(LightLayer.BLOCK, pos).toByte()
                    }
                    if (state.block is BaseTorchBlock) torchList.add(pos.asLong())
                    floorBelow = state.isFaceSturdy(level, pos, Direction.UP)
                }
            }
        }
        torches = torchList.toLongArray()
    }
}
