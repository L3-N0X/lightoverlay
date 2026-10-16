package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Works out where the next torches should go, see [TorchHelperMode].
 *
 * Every few ticks the client thread copies the blocks around the player into a [LightSnapshot] (the scan radius plus
 * [LightOverlayConfig.lookAhead]), and a background thread turns it into a [Plan]: it simulates the light of a torch
 * on every candidate floor and lets [TorchOptimizer] pick positions that light up all of it, or it lays a
 * [GridPattern] over the floor. Dark floor in the look-ahead area counts half, so torches near the edge of the scan
 * area also make use of the dark beyond it, without the suggestions drifting out of view.
 *
 * Smart mode remembers its positions (within [MEMORY_RADIUS]) and keeps them while they're still useful, so the
 * suggestions stay put while the player moves and come back the same when the player returns. The next torch is the
 * one that lights the dark floor closest to the player, counted in steps around walls.
 */
object TorchPlanner {
    enum class SpotKind {
        /** Where the next torch should go. */
        NEXT,

        /** Further suggested torch positions, or grid positions that still need a torch. */
        SUGGESTED,

        /** Grid positions that wouldn't light up anything new. */
        GRID_LIT,

        /** Grid positions that already have a light source on or next to them. */
        GRID_DONE
    }

    class Spot(val x: Int, val y: Int, val z: Int, val kind: SpotKind)

    /** @param preview positions ([BlockPos.asLong]) of the dark floor the next torch would light up */
    class Plan(val spots: List<Spot>, val preview: LongArray) {
        val isEmpty get() = spots.isEmpty()

        companion object {
            val EMPTY = Plan(emptyList(), LongArray(0))
        }
    }

    /** Light level of a regular torch, used unless a different light source is held. */
    private const val TORCH_EMISSION = 14

    /** The planner works on at most this horizontal radius, however large the scan radius is. */
    private const val MAX_RADIUS = 24
    private const val MAX_VERTICAL_RADIUS = 12
    private const val MAX_VERTICAL_LOOK_AHEAD = 4
    private const val MIN_INTERVAL_TICKS = 10

    /** Weight of dark floor outside the scan area, compared to 1 inside. */
    private const val LOOK_AHEAD_WEIGHT = 0.5f

    /** Score taken off per block of distance to the player, only breaks ties (the smallest weight is 0.5). */
    private const val DISTANCE_PENALTY = 0.01f

    /** New smart positions per plan, the closest dark floor gets them first. */
    private const val MAX_NEW_SPOTS = 64

    /** Smart positions further away than this (horizontally) are forgotten. */
    private const val MEMORY_RADIUS = 96

    /** The next torch only moves to another position if that one lights dark floor this many steps closer. */
    private const val NEXT_HYSTERESIS = 3

    /** Cost of dark floor the player can't walk to inside the snapshot (behind a wall), on top of its distance. */
    private const val UNREACHABLE_COST = 100_000

    @Volatile
    var plan: Plan = Plan.EMPTY
        private set

    /** Grid origin set with the pin key, overrides [LightOverlayConfig.gridAnchor]. */
    private var pinned: BlockPos? = null
    private var newestTorch: BlockPos? = null
    private var knownTorches: Set<Long>? = null
    private var lastSnapshot: LightSnapshot? = null
    private var lastRequest: Request? = null
    private var lastLevel: Level? = null

    private var ticksUntilPlan = 0
    private var job: Future<*>? = null

    /** Bumped whenever the plan is cleared, so a job that is still running can't publish an outdated plan. */
    @Volatile
    private var generation = 0

    /** The last smart plan, only touched by the planner thread. Belongs to a [generation], older ones are ignored. */
    private class Memory(val generation: Int, val spots: List<Remembered>, val next: Long?)

    /** A smart position ([BlockPos.asLong]) and the gain it had when it was picked. */
    private class Remembered(val pos: Long, val gain: Float)

    private var memory = Memory(-1, emptyList(), null)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Light Overlay torch planner").apply { isDaemon = true }
    }

    fun tick(mc: Minecraft) {
        val level = mc.level
        val player = mc.player
        if (level !== lastLevel) {
            lastLevel = level
            pinned = null
            generation++
            forgetTorches()
        }
        val mode = LightOverlayConfig.torchHelperMode
        if (!LightOverlayMod.active || mode == TorchHelperMode.OFF || level == null || player == null) {
            clear()
            return
        }

        if (--ticksUntilPlan > 0 || job?.isDone == false) return
        ticksUntilPlan = maxOf(LightOverlayConfig.updateIntervalTicks, MIN_INTERVAL_TICKS)

        val center = player.blockPosition()
        val radius = minOf(LightOverlayConfig.horizontalRadius, MAX_RADIUS)
        val vertical = minOf(LightOverlayConfig.verticalRadius, MAX_VERTICAL_RADIUS)
        val lookAhead = LightOverlayConfig.lookAhead
        val verticalLookAhead = minOf(lookAhead, MAX_VERTICAL_LOOK_AHEAD)
        val snapshot = LightSnapshot.take(
            level,
            center.x - radius - lookAhead, center.y - vertical - verticalLookAhead, center.z - radius - lookAhead,
            center.x + radius + lookAhead, center.y + vertical + verticalLookAhead, center.z + radius + lookAhead
        )
        val previousSnapshot = lastSnapshot
        trackTorches(snapshot, player.x, player.z)

        val emission = LightOverlayMod.heldLightEmission(player) ?: TORCH_EMISSION
        val request = Request(
            mode = mode,
            centerX = center.x, centerY = center.y, centerZ = center.z,
            radius = radius, vertical = vertical,
            playerX = player.x, playerY = player.y, playerZ = player.z,
            emission = emission,
            minLight = minOf(LightOverlayConfig.minLightLevel, emission),
            count = LightOverlayConfig.suggestionCount,
            pattern = LightOverlayConfig.gridPattern,
            reach = maxOf(1, emission - LightOverlayConfig.minLightLevel - LightOverlayConfig.gridMargin),
            anchor = gridAnchor(snapshot, player.x, player.z),
            sphere = FarmLock.sphere
        )
        // Nothing changed since the last plan (e.g. standing still), it would come out the same.
        val key = request.copy(playerX = 0.0, playerY = 0.0, playerZ = 0.0)
        if (key == lastRequest && snapshot.sameAs(previousSnapshot)) return
        lastRequest = key
        val jobGeneration = generation
        job = executor.submit {
            try {
                val result = compute(snapshot, request, jobGeneration)
                if (generation == jobGeneration) plan = result
            } catch (e: Exception) {
                LightOverlayMod.LOGGER.error("Torch planning failed", e)
            }
        }
    }

    private fun clear() {
        if (plan !== Plan.EMPTY) plan = Plan.EMPTY
        generation++
        lastRequest = null
        ticksUntilPlan = 0
        forgetTorches()
    }

    private fun forgetTorches() {
        newestTorch = null
        knownTorches = null
        lastSnapshot = null
    }

    /**
     * Pins the grid to the block column the player looks at, or unpins it when it is already pinned there.
     * Switches the helper to grid mode, as that's the only one with a grid.
     */
    fun togglePin(mc: Minecraft): Component {
        val player = mc.player ?: return Component.empty()
        val hit = mc.hitResult
        val target = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            // The column in front of a wall, or the one of the floor or ceiling looked at.
            if (hit.direction.axis == Direction.Axis.Y) hit.blockPos else hit.blockPos.relative(hit.direction)
        } else {
            player.blockPosition()
        }

        val current = pinned
        if (current != null && current.x == target.x && current.z == target.z) {
            pinned = null
            ticksUntilPlan = 0
            return Component.translatable("lightoverlay.message.grid_unpinned")
        }
        pinned = target
        ticksUntilPlan = 0
        if (LightOverlayConfig.torchHelperMode != TorchHelperMode.GRID) {
            LightOverlayConfig.torchHelperMode = TorchHelperMode.GRID
            LightOverlayConfig.save()
        }
        return Component.translatable("lightoverlay.message.grid_pinned", target.x, target.z)
    }

    /** Switches to the next [TorchHelperMode]. */
    fun cycleMode(): Component {
        val modes = TorchHelperMode.entries
        val mode = modes[(LightOverlayConfig.torchHelperMode.ordinal + 1) % modes.size]
        LightOverlayConfig.torchHelperMode = mode
        LightOverlayConfig.save()
        ticksUntilPlan = 0
        return Component.translatable(
            "lightoverlay.message.torch_helper",
            Component.translatable("lightoverlay.config.torch_helper_mode.${mode.name.lowercase()}")
        )
    }

    /**
     * Remembers the torches in [snapshot]. A torch that wasn't there in the previous snapshot, although its position
     * was inside it, has just been placed (or lit up by a neighbour) and becomes the [newestTorch].
     */
    private fun trackTorches(snapshot: LightSnapshot, playerX: Double, playerZ: Double) {
        val previous = knownTorches
        val bounds = lastSnapshot
        if (previous != null && bounds != null) {
            snapshot.torches
                .filter { it !in previous && bounds.contains(BlockPos.getX(it), BlockPos.getY(it), BlockPos.getZ(it)) }
                .minByOrNull { horizontalDistanceSq(it, playerX, playerZ) }
                ?.let { newestTorch = BlockPos.of(it) }
        }
        knownTorches = snapshot.torches.toHashSet()
        lastSnapshot = snapshot
    }

    /** The (x, z) column the grid lines up with. */
    private fun gridAnchor(snapshot: LightSnapshot, playerX: Double, playerZ: Double): Pair<Int, Int> {
        val nearest = { snapshot.torches.minByOrNull { horizontalDistanceSq(it, playerX, playerZ) }?.let(BlockPos::of) }
        val anchor = pinned ?: when (LightOverlayConfig.gridAnchor) {
            GridAnchor.WORLD -> null
            GridAnchor.NEAREST_TORCH -> nearest()
            GridAnchor.NEWEST_TORCH -> newestTorch ?: nearest()
        }
        return if (anchor == null) 0 to 0 else anchor.x to anchor.z
    }

    private fun horizontalDistanceSq(pos: Long, x: Double, z: Double): Double {
        val dx = BlockPos.getX(pos) + 0.5 - x
        val dz = BlockPos.getZ(pos) + 0.5 - z
        return dx * dx + dz * dz
    }

    /** Everything the background job needs, so it never reads the config or the world. */
    private data class Request(
        val mode: TorchHelperMode,
        val centerX: Int, val centerY: Int, val centerZ: Int,
        /** The scan area around the center; the snapshot reaches further by the look-ahead. */
        val radius: Int, val vertical: Int,
        val playerX: Double, val playerY: Double, val playerZ: Double,
        val emission: Int,
        val minLight: Int,
        val count: Int,
        val pattern: GridPattern,
        /** Blocks a torch's light reaches on flat ground, the grid spacing follows from it. */
        val reach: Int,
        val anchor: Pair<Int, Int>,
        /** While locked, only dark floor inside the [FarmLock] spawn shell needs light. */
        val sphere: FarmLock.Sphere?
    ) {
        fun inScanArea(x: Int, y: Int, z: Int): Boolean =
            abs(x - centerX) <= radius && abs(z - centerZ) <= radius && abs(y - centerY) <= vertical

        fun distanceTo(x: Int, y: Int, z: Int): Double {
            val dx = x + 0.5 - playerX
            val dy = y - playerY
            val dz = z + 0.5 - playerZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    /** Dark floor cells of a snapshot, numbered as targets. */
    private class Targets(private val snapshot: LightSnapshot, private val request: Request) {
        /** Target id per cell, -1 for cells that don't need light. */
        val idOf = IntArray(snapshot.cellCount) { -1 }

        /** Cell per target id. */
        val cells: IntArray
        val weights: FloatArray

        init {
            val cellList = IntArrayList()
            val weightList = ArrayList<Float>()
            for (cell in 0 until snapshot.cellCount) {
                if (!snapshot.isFloor(cell) || snapshot.blockLight(cell) >= request.minLight) continue
                if (request.sphere?.contains(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell)) == false) continue
                idOf[cell] = weightList.size
                cellList.add(cell)
                weightList.add(weightOf(cell))
            }
            cells = cellList.toArray()
            weights = weightList.toFloatArray()
        }

        fun inScanArea(cell: Int) = request.inScanArea(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))

        private fun weightOf(cell: Int) = if (inScanArea(cell)) 1f else LOOK_AHEAD_WEIGHT

        /** Ids of the targets a torch on [cell] would light up. */
        fun coveredBy(spreader: LightSnapshot.Spreader, cell: Int): IntArray {
            val covers = IntArrayList()
            spreader.spread(cell, request.emission, request.minLight) { reached, _ ->
                val id = idOf[reached]
                if (id >= 0) covers.add(id)
            }
            return covers.toArray()
        }

        /** A torch on [cell] as a [TorchOptimizer.Candidate], or null if it wouldn't light up any dark floor. */
        fun candidate(spreader: LightSnapshot.Spreader, cell: Int, pickedGain: Float = 0f): TorchOptimizer.Candidate? {
            val covers = coveredBy(spreader, cell)
            if (covers.isEmpty()) return null
            val distance = request.distanceTo(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))
            return TorchOptimizer.Candidate(cell, covers, (distance * DISTANCE_PENALTY).toFloat(), pickedGain)
        }
    }

    private fun compute(snapshot: LightSnapshot, request: Request, generation: Int): Plan {
        val targets = Targets(snapshot, request)
        val spreader = snapshot.Spreader()
        return when (request.mode) {
            TorchHelperMode.SMART -> planSmart(snapshot, request, targets, spreader, generation)
            TorchHelperMode.GRID -> planGrid(snapshot, request, targets, spreader)
            TorchHelperMode.OFF -> Plan.EMPTY
        }
    }

    private fun planSmart(
        snapshot: LightSnapshot,
        request: Request,
        targets: Targets,
        spreader: LightSnapshot.Spreader,
        generation: Int
    ): Plan {
        val remembered = memory.takeIf { it.generation == generation }

        // How far the player has to go to each cell, around walls. Cells behind a wall come after all others.
        val playerY = request.centerY.coerceIn(snapshot.minY, snapshot.maxY)
        val steps = snapshot.distancesFrom(snapshot.index(request.centerX, playerY, request.centerZ))
        fun cost(cell: Int): Int {
            if (steps[cell] != LightSnapshot.UNREACHABLE) return steps[cell]
            return UNREACHABLE_COST + abs(snapshot.xOf(cell) - request.centerX) +
                abs(snapshot.yOf(cell) - playerY) + abs(snapshot.zOf(cell) - request.centerZ)
        }

        // Remembered positions inside the snapshot are checked again, the others are kept as they are.
        val kept = ArrayList<TorchOptimizer.Candidate>()
        val keptElsewhere = HashSet<Remembered>()
        for (spot in remembered?.spots.orEmpty()) {
            val x = BlockPos.getX(spot.pos)
            val y = BlockPos.getY(spot.pos)
            val z = BlockPos.getZ(spot.pos)
            val dx = x - request.centerX
            val dz = z - request.centerZ
            if (dx * dx + dz * dz > MEMORY_RADIUS * MEMORY_RADIUS) continue
            if (!snapshot.contains(x, y, z)) {
                keptElsewhere.add(spot)
                continue
            }
            val cell = snapshot.index(x, y, z)
            if (snapshot.isFloor(cell)) targets.candidate(spreader, cell, spot.gain)?.let(kept::add)
        }

        val chosen = if (targets.weights.isEmpty()) {
            emptyList()
        } else {
            val nearDark = nearDarkColumns(snapshot, targets, request.emission - request.minLight)
            val candidates = ArrayList<TorchOptimizer.Candidate>()
            for (cell in 0 until snapshot.cellCount) {
                if (!snapshot.isFloor(cell) || !nearDark[cell / snapshot.sizeY] || !targets.inScanArea(cell)) continue
                targets.candidate(spreader, cell)?.let(candidates::add)
            }
            // The dark floor in the scan area, closest to the player first, packed as cost << 32 | target.
            val order = targets.cells.indices
                .filter { targets.inScanArea(targets.cells[it]) }
                .map { cost(targets.cells[it]).toLong() shl 32 or it.toLong() }
                .toLongArray()
                .apply { sort() }
                .let { packed -> IntArray(packed.size) { packed[it].toInt() } }
            TorchOptimizer.plan(kept, candidates, targets.weights, order, MAX_NEW_SPOTS)
        }

        // The next torch lights the dark floor closest to the player; the one nearest to the player if several do.
        class Ranked(val candidate: TorchOptimizer.Candidate, val pos: Long, val darkCost: Int, val cost: Int)
        val ranked = chosen.map { candidate ->
            val cell = candidate.cell
            Ranked(
                candidate,
                BlockPos.asLong(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell)),
                candidate.covers.minOf { cost(targets.cells[it]) },
                cost(cell)
            )
        }
        val shown = ranked
            .filter { targets.inScanArea(it.candidate.cell) }
            .sortedWith(compareBy<Ranked> { it.darkCost }.thenBy { it.cost })
        var next = shown.firstOrNull()
        // Stay with the previous next torch unless another one is clearly closer, so it doesn't flip back and forth.
        val previousNext = shown.find { it.pos == remembered?.next }
        if (next != null && previousNext != null && previousNext.darkCost <= next.darkCost + NEXT_HYSTERESIS) next = previousNext

        // Kept positions come first in the plan, in their original order, so older ones keep winning where they
        // overlap. Their gain stays the one they were picked with, so a position that slowly loses its use is dropped.
        val keptHere = ranked.filter { it.candidate.pickedGain > 0f }.associateBy { it.pos }
        val spots = ArrayList<Remembered>(keptElsewhere.size + ranked.size)
        for (spot in remembered?.spots.orEmpty()) {
            if (spot in keptElsewhere || spot.pos in keptHere) spots.add(spot)
        }
        for (it in ranked) if (it.pos !in keptHere) spots.add(Remembered(it.pos, it.candidate.gain))
        memory = Memory(generation, spots, next?.pos)

        if (next == null) return Plan.EMPTY
        val suggested = shown.asSequence().filter { it !== next }.take(request.count - 1)
        val planSpots = listOf(spot(snapshot, next.candidate.cell, SpotKind.NEXT)) +
            suggested.map { spot(snapshot, it.candidate.cell, SpotKind.SUGGESTED) }
        return Plan(planSpots, preview(snapshot, targets, next.candidate.covers))
    }

    /**
     * Per column (x, z) of the snapshot, whether a dark floor is within [reach] blocks horizontally (taxicab, like
     * light), found with a breadth-first search from all dark columns. Torches anywhere else can't light up anything.
     */
    private fun nearDarkColumns(snapshot: LightSnapshot, targets: Targets, reach: Int): BooleanArray {
        val sizeZ = snapshot.sizeZ
        val columns = snapshot.sizeX * sizeZ
        val distance = IntArray(columns) { Int.MAX_VALUE }
        val queue = IntArray(columns)
        var head = 0
        var tail = 0
        for (cell in 0 until snapshot.cellCount) {
            val column = cell / snapshot.sizeY
            if (targets.idOf[cell] >= 0 && distance[column] != 0) {
                distance[column] = 0
                queue[tail++] = column
            }
        }
        while (head < tail) {
            val column = queue[head++]
            val next = distance[column] + 1
            if (next > reach) continue
            val x = column / sizeZ
            val z = column % sizeZ
            fun visit(neighbour: Int) {
                if (distance[neighbour] > next) {
                    distance[neighbour] = next
                    queue[tail++] = neighbour
                }
            }
            if (x > 0) visit(column - sizeZ)
            if (x < snapshot.sizeX - 1) visit(column + sizeZ)
            if (z > 0) visit(column - 1)
            if (z < sizeZ - 1) visit(column + 1)
        }
        return BooleanArray(columns) { distance[it] <= reach }
    }

    private fun planGrid(snapshot: LightSnapshot, request: Request, targets: Targets, spreader: LightSnapshot.Spreader): Plan {
        val (ax, az, bx, bz) = gridBasis(request.pattern, request.reach)
        val (anchorX, anchorZ) = request.anchor
        val minX = request.centerX - request.radius
        val maxX = request.centerX + request.radius
        val minZ = request.centerZ - request.radius
        val maxZ = request.centerZ + request.radius

        // Lattice coordinates (i, j) of the scan area's corners bound the lattice points to try.
        val det = (ax * bz - az * bx).toDouble()
        var minI = Double.MAX_VALUE
        var maxI = -Double.MAX_VALUE
        var minJ = Double.MAX_VALUE
        var maxJ = -Double.MAX_VALUE
        for (cx in intArrayOf(minX, maxX)) for (cz in intArrayOf(minZ, maxZ)) {
            val dx = (cx - anchorX).toDouble()
            val dz = (cz - anchorZ).toDouble()
            val i = (dx * bz - dz * bx) / det
            val j = (ax * dz - az * dx) / det
            minI = minOf(minI, i); maxI = maxOf(maxI, i)
            minJ = minOf(minJ, j); maxJ = maxOf(maxJ, j)
        }

        val playerY = floor(request.playerY).toInt()
        class GridSpot(val cell: Int, val kind: SpotKind, val covers: IntArray, val distance: Double)
        val gridSpots = ArrayList<GridSpot>()
        for (i in floor(minI).toInt()..ceil(maxI).toInt()) {
            for (j in floor(minJ).toInt()..ceil(maxJ).toInt()) {
                val x = anchorX + i * ax + j * bx
                val z = anchorZ + i * az + j * bz
                if (x !in minX..maxX || z !in minZ..maxZ) continue
                val cell = snapshot.floorNear(x, z, playerY, request.centerY - request.vertical, request.centerY + request.vertical)
                if (cell < 0) continue
                val y = snapshot.yOf(cell)
                val distance = request.distanceTo(x, y, z)
                // A light source of about the same strength is already on or next to this spot.
                if (snapshot.blockLight(cell) >= request.emission - 1) {
                    gridSpots.add(GridSpot(cell, SpotKind.GRID_DONE, IntArray(0), distance))
                    continue
                }
                val covers = targets.coveredBy(spreader, cell)
                val kind = if (covers.isEmpty()) SpotKind.GRID_LIT else SpotKind.SUGGESTED
                gridSpots.add(GridSpot(cell, kind, covers, distance))
            }
        }

        val next = gridSpots.filter { it.kind == SpotKind.SUGGESTED }.minByOrNull { it.distance }
        val spots = gridSpots.map { spot(snapshot, it.cell, if (it === next) SpotKind.NEXT else it.kind) }
        return Plan(spots, if (next == null) LongArray(0) else preview(snapshot, targets, next.covers))
    }

    /**
     * Two lattice vectors (ax, az, bx, bz) for [pattern], so that every block on flat ground is within [reach]
     * blocks (taxicab) of a lattice point.
     */
    private fun gridBasis(pattern: GridPattern, reach: Int): IntArray = when (pattern) {
        // The light diamonds of radius r tile the plane with this lattice (a perfect Lee code): 2r² + 2r + 1 blocks per torch.
        GridPattern.DIAMOND -> intArrayOf(reach, reach + 1, reach + 1, -reach)
        // Rows r apart, torches 2r apart in a row, shifted by r every other row: 2r² blocks per torch.
        GridPattern.STAGGERED -> intArrayOf(reach, reach, reach, -reach)
        // The middle of a square of side s is 2 * floor(s / 2) away from its corners.
        GridPattern.SQUARE -> {
            val side = reach - reach % 2 + 1
            intArrayOf(side, 0, 0, side)
        }
    }

    private fun spot(snapshot: LightSnapshot, cell: Int, kind: SpotKind) =
        Spot(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell), kind)

    private fun preview(snapshot: LightSnapshot, targets: Targets, covers: IntArray): LongArray {
        return LongArray(covers.size) {
            val cell = targets.cells[covers[it]]
            BlockPos.asLong(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))
        }
    }

    private class IntArrayList {
        private var data = IntArray(64)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun isEmpty() = size == 0
        fun toArray(): IntArray = data.copyOf(size)
    }
}
