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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Works out where the next torches should go, see [TorchHelperMode].
 *
 * Every few ticks the client thread copies the blocks around the player into a [LightSnapshot] (the scan radius plus
 * [LightOverlayConfig.lookAhead], and the look-ahead once more in the direction the player walks), and a background
 * thread turns it into a [Plan]: it simulates the light of a torch on every candidate floor and lets [TorchOptimizer]
 * pick positions that light up all dark floor in the scan area, or it lays a [GridPattern] over the floor. Torches
 * may go beyond the scan area as far as their light reaches back into it, so the next torch into a dark area goes
 * into its middle rather than on its edge.
 *
 * Smart mode remembers its settled positions (within [MEMORY_RADIUS]) and keeps them while they're still useful, so
 * the suggestions stay put while the player moves and come back the same when the player returns. Positions that
 * depend on floor beyond the snapshot are provisional: they are planned again every time, but stick to where they
 * were unless a clearly better one turns up, and settle once the player comes close enough to see all of it. The
 * next torch is the one that lights the dark floor closest to the player, counted in steps around walls.
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

    /** Ticks between plans, independent of the overlay's update interval so the suggestions keep up with walking. */
    private const val PLAN_INTERVAL_TICKS = 5

    /** How fast the walking direction follows the player's movement, per tick. */
    private const val HEADING_SMOOTHING = 0.15

    /** Below this smoothed speed (blocks per tick, walking is about 0.22) the player counts as standing still. */
    private const val MIN_HEADING_SPEED = 0.05

    /** Moves further than this within a tick (squared) are teleports and reset the walking direction. */
    private const val TELEPORT_DISTANCE_SQ = 16.0

    /** Score taken off per block of distance to the player, only breaks ties (every dark floor counts 1). */
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

    /**
     * The last smart plan, only touched by the planner thread. Belongs to a [generation], older ones are ignored.
     *
     * @param spots the settled positions
     * @param provisional the provisional positions ([BlockPos.asLong]), see [TorchOptimizer.Candidate.sticky]
     */
    private class Memory(val generation: Int, val spots: List<Remembered>, val provisional: Set<Long>, val next: Long?)

    /** A smart position ([BlockPos.asLong]) and the gain it had when it was picked. */
    private class Remembered(val pos: Long, val gain: Float)

    private var memory = Memory(-1, emptyList(), emptySet(), null)

    /** The player's smoothed movement per tick, see [trackHeading]. */
    private var headingX = 0.0
    private var headingZ = 0.0
    private var lastPlayerX = Double.NaN
    private var lastPlayerZ = Double.NaN

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
            lastPlayerX = Double.NaN
        }
        val mode = LightOverlayConfig.torchHelperMode
        if (!LightOverlayMod.active || mode == TorchHelperMode.OFF || level == null || player == null) {
            clear()
            return
        }
        trackHeading(player.x, player.z)

        if (--ticksUntilPlan > 0 || job?.isDone == false) return
        ticksUntilPlan = PLAN_INTERVAL_TICKS

        val center = player.blockPosition()
        val radius = minOf(LightOverlayConfig.horizontalRadius, MAX_RADIUS)
        val vertical = minOf(LightOverlayConfig.verticalRadius, MAX_VERTICAL_RADIUS)
        val lookAhead = LightOverlayConfig.lookAhead
        val verticalLookAhead = minOf(lookAhead, MAX_VERTICAL_LOOK_AHEAD)
        // The look-ahead counts twice in the walking direction, so the torches ahead are seen in full earlier.
        val speed = sqrt(headingX * headingX + headingZ * headingZ)
        val aheadX = if (speed < MIN_HEADING_SPEED) 0.0 else headingX / speed * lookAhead
        val aheadZ = if (speed < MIN_HEADING_SPEED) 0.0 else headingZ / speed * lookAhead
        val snapshot = LightSnapshot.take(
            level,
            center.x - radius - lookAhead - maxOf(0.0, -aheadX).roundToInt(),
            center.y - vertical - verticalLookAhead,
            center.z - radius - lookAhead - maxOf(0.0, -aheadZ).roundToInt(),
            center.x + radius + lookAhead + maxOf(0.0, aheadX).roundToInt(),
            center.y + vertical + verticalLookAhead,
            center.z + radius + lookAhead + maxOf(0.0, aheadZ).roundToInt()
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
            adaptive = LightOverlayConfig.gridPattern == GridPattern.STAGGERED && LightOverlayConfig.gridAdaptiveSpacing,
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
        lastPlayerX = Double.NaN
    }

    /** Follows the player's horizontal movement, smoothed so a short stop or sidestep doesn't turn it around. */
    private fun trackHeading(x: Double, z: Double) {
        val dx = x - lastPlayerX
        val dz = z - lastPlayerZ
        if (lastPlayerX.isNaN() || dx * dx + dz * dz > TELEPORT_DISTANCE_SQ) {
            headingX = 0.0
            headingZ = 0.0
        } else {
            headingX += (dx - headingX) * HEADING_SMOOTHING
            headingZ += (dz - headingZ) * HEADING_SMOOTHING
        }
        lastPlayerX = x
        lastPlayerZ = z
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
        /** Lay the rows out with [AdaptiveRows] instead of the regular lattice. */
        val adaptive: Boolean,
        val anchor: Pair<Int, Int>,
        /** While locked, only dark floor inside the [FarmLock] spawn shell needs light. */
        val sphere: FarmLock.Sphere?
    ) {
        fun inScanArea(x: Int, y: Int, z: Int): Boolean =
            abs(x - centerX) <= radius && abs(z - centerZ) <= radius && abs(y - centerY) <= vertical

        /** Blocks a torch lights up in every direction, ignoring walls. */
        val lightReach get() = emission - minLight

        /** Columns whose torches can light floor in the scan area, i.e. the scan area grown by [lightReach]. */
        fun inTorchArea(x: Int, z: Int): Boolean =
            abs(x - centerX) <= radius + lightReach && abs(z - centerZ) <= radius + lightReach

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

        init {
            val cellList = IntArrayList()
            for (cell in 0 until snapshot.cellCount) {
                if (!snapshot.isFloor(cell) || snapshot.blockLight(cell) >= request.minLight) continue
                if (request.sphere?.contains(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell)) == false) continue
                idOf[cell] = cellList.size
                cellList.add(cell)
            }
            cells = cellList.toArray()
        }

        fun inScanArea(cell: Int) = request.inScanArea(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))

        fun inTorchArea(cell: Int) = request.inTorchArea(snapshot.xOf(cell), snapshot.zOf(cell))

        /** Ids of the targets a torch on [cell] would light up. */
        fun coveredBy(spreader: LightSnapshot.Spreader, cell: Int): IntArray = coverage(spreader, cell).covers

        /**
         * The targets a torch on [cell] would light up, and whether its light reaches the side of the snapshot with
         * enough left to go on, so it may light more floor beyond. The top and bottom don't count: floor that far up
         * or down rarely matters, and it would leave hardly any position settled.
         */
        private fun coverage(spreader: LightSnapshot.Spreader, cell: Int): Coverage {
            val covers = IntArrayList()
            var truncated = false
            spreader.spread(cell, request.emission, request.minLight) { reached, light ->
                val id = idOf[reached]
                if (id >= 0) covers.add(id)
                if (light > request.minLight && !truncated) {
                    val x = snapshot.xOf(reached)
                    val z = snapshot.zOf(reached)
                    truncated = x == snapshot.minX || x == snapshot.maxX || z == snapshot.minZ || z == snapshot.maxZ
                }
            }
            return Coverage(covers.toArray(), truncated)
        }

        private class Coverage(val covers: IntArray, val truncated: Boolean)

        /** A torch on [cell] as a [TorchOptimizer.Candidate], or null if it wouldn't light up any dark floor. */
        fun candidate(
            spreader: LightSnapshot.Spreader,
            cell: Int,
            sticky: Boolean = false,
            pickedGain: Float = 0f
        ): TorchOptimizer.Candidate? {
            val coverage = coverage(spreader, cell)
            if (coverage.covers.isEmpty()) return null
            val distance = request.distanceTo(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))
            return TorchOptimizer.Candidate(
                cell, coverage.covers, coverage.truncated, -(distance * DISTANCE_PENALTY).toFloat(), sticky,
                // Part of what it lit may now be beyond the snapshot, so its gain can't be compared any more.
                if (coverage.truncated) 0f else pickedGain
            )
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
            if (snapshot.isFloor(cell)) targets.candidate(spreader, cell, pickedGain = spot.gain)?.let(kept::add)
        }
        val keptCandidates = kept.toHashSet()

        val chosen = if (targets.cells.isEmpty()) {
            emptyList()
        } else {
            val provisional = remembered?.provisional.orEmpty()
            val nearDark = nearDarkColumns(snapshot, targets, request.lightReach)
            val candidates = ArrayList<TorchOptimizer.Candidate>()
            for (cell in 0 until snapshot.cellCount) {
                if (!snapshot.isFloor(cell) || !nearDark[cell / snapshot.sizeY] || !targets.inTorchArea(cell)) continue
                val sticky = BlockPos.asLong(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell)) in provisional
                targets.candidate(spreader, cell, sticky)?.let(candidates::add)
            }
            // The dark floor in the scan area, closest to the player first, packed as cost << 32 | target.
            val order = targets.cells.indices
                .filter { targets.inScanArea(targets.cells[it]) }
                .map { cost(targets.cells[it]).toLong() shl 32 or it.toLong() }
                .toLongArray()
                .apply { sort() }
                .let { packed -> IntArray(packed.size) { packed[it].toInt() } }
            TorchOptimizer.plan(kept, candidates, targets.cells.size, order, MAX_NEW_SPOTS)
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
            .filter { targets.inTorchArea(it.candidate.cell) }
            .sortedWith(compareBy<Ranked> { it.darkCost }.thenBy { it.cost })
        var next = shown.firstOrNull()
        // Stay with the previous next torch unless another one is clearly closer, so it doesn't flip back and forth.
        val previousNext = shown.find { it.pos == remembered?.next }
        if (next != null && previousNext != null && previousNext.darkCost <= next.darkCost + NEXT_HYSTERESIS) next = previousNext

        // Kept positions come first in the plan, in their original order, so older ones keep winning where they
        // overlap. Their gain stays the one they were picked with, so a position that slowly loses its use is dropped.
        // New positions are only remembered once settled, the provisional ones are planned again next time.
        val keptHere = ranked.filter { it.candidate in keptCandidates }.mapTo(HashSet()) { it.pos }
        val spots = ArrayList<Remembered>(keptElsewhere.size + ranked.size)
        for (spot in remembered?.spots.orEmpty()) {
            if (spot in keptElsewhere || spot.pos in keptHere) spots.add(spot)
        }
        val provisional = HashSet<Long>()
        for (it in ranked) {
            if (it.pos in keptHere) continue
            if (it.candidate.settled) spots.add(Remembered(it.pos, it.candidate.gain)) else provisional.add(it.pos)
        }
        memory = Memory(generation, spots, provisional, next?.pos)

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
        val playerY = floor(request.playerY).toInt()
        val cells = if (request.adaptive) {
            AdaptiveRows(snapshot, request, spreader, playerY).cells()
        } else {
            latticeCells(snapshot, request, playerY)
        }

        class GridSpot(val cell: Int, val kind: SpotKind, val covers: IntArray, val distance: Double)
        val gridSpots = ArrayList<GridSpot>()
        for (cell in cells) {
            val distance = request.distanceTo(snapshot.xOf(cell), snapshot.yOf(cell), snapshot.zOf(cell))
            // A light source of about the same strength is already on or next to this spot.
            if (snapshot.blockLight(cell) >= request.emission - 1) {
                gridSpots.add(GridSpot(cell, SpotKind.GRID_DONE, IntArray(0), distance))
                continue
            }
            val covers = targets.coveredBy(spreader, cell)
            val kind = if (covers.isEmpty()) SpotKind.GRID_LIT else SpotKind.SUGGESTED
            gridSpots.add(GridSpot(cell, kind, covers, distance))
        }

        val next = gridSpots.filter { it.kind == SpotKind.SUGGESTED }.minByOrNull { it.distance }
        val spots = gridSpots.map { spot(snapshot, it.cell, if (it === next) SpotKind.NEXT else it.kind) }
        return Plan(spots, if (next == null) LongArray(0) else preview(snapshot, targets, next.covers))
    }

    /** The floor cells of the [GridPattern] lattice points in the scan area. */
    private fun latticeCells(snapshot: LightSnapshot, request: Request, playerY: Int): List<Int> {
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

        val cells = ArrayList<Int>()
        for (i in floor(minI).toInt()..ceil(maxI).toInt()) {
            for (j in floor(minJ).toInt()..ceil(maxJ).toInt()) {
                val x = anchorX + i * ax + j * bx
                val z = anchorZ + i * az + j * bz
                if (x !in minX..maxX || z !in minZ..maxZ) continue
                val cell = snapshot.floorNear(x, z, playerY, request.centerY - request.vertical, request.centerY + request.vertical)
                if (cell >= 0) cells.add(cell)
            }
        }
        return cells
    }

    /**
     * [GridPattern.STAGGERED] with the gaps along each row fitted to the terrain. The rows stay where the lattice
     * has them, [Request.reach] apart. Along a row, each torch goes as far from the previous one as the regular gap
     * (twice the reach) allows while the floor in between, and between this row and the one laid out before it, still
     * gets light; on uneven ground light loses levels climbing up and down, so the gap shrinks there, down to the reach.
     * Rows are laid out from the anchor's row outwards, each against the one before, starting at a lattice point, so
     * flat ground comes out as the regular lattice.
     *
     * The floor is a heightmap: per column the floor closest to the player's height, the same one the spots stand on.
     * The light of torches in the snapshot is simulated, torches beyond it are taken to stand on flat ground. Floor no
     * torch in reach could light (a tree top, a deep hole) doesn't count.
     */
    private class AdaptiveRows(
        private val snapshot: LightSnapshot,
        private val request: Request,
        private val spreader: LightSnapshot.Spreader,
        playerY: Int
    ) {
        private val reach = request.reach
        private val sizeZ = snapshot.sizeZ

        /** Floor cell per column, -1 if there is none. Columns are numbered like the snapshot's (x * sizeZ + z). */
        private val heightmap = IntArray(snapshot.sizeX * sizeZ) { column ->
            snapshot.floorNear(
                snapshot.minX + column / sizeZ, snapshot.minZ + column % sizeZ,
                playerY, request.centerY - request.vertical, request.centerY + request.vertical
            )
        }

        /** Columns whose floor a torch at (x, z) lights up, keyed by [key]. */
        private val litCache = HashMap<Long, IntArray>()

        /** Columns lit by the torches placed so far in the current row and the one before it. */
        private val lit = BooleanArray(heightmap.size)

        /** Marks columns some candidate of the current step lights ([coverableStamp]) or the one checked ([candidateStamp]). */
        private val coverable = IntArray(heightmap.size)
        private val candidate = IntArray(heightmap.size)
        private var coverableStamp = 0
        private var candidateStamp = 0

        /** The floor cells of the torches in the scan area. */
        fun cells(): List<Int> {
            val (anchorX, anchorZ) = request.anchor
            val rows = HashMap<Int, IntArray>()
            val first = startIndex(anchorX, snapshot.minX, snapshot.maxX, 0, 1)
            rows[first] = layRow(first, null, anchorX, anchorZ)
            var p = first
            while (anchorX + p * reach <= snapshot.maxX) {
                rows[p + 1] = layRow(p + 1, p to rows.getValue(p), anchorX, anchorZ)
                p++
            }
            p = first
            while (anchorX + p * reach >= snapshot.minX) {
                rows[p - 1] = layRow(p - 1, p to rows.getValue(p), anchorX, anchorZ)
                p--
            }

            val minX = request.centerX - request.radius
            val maxX = request.centerX + request.radius
            val minZ = request.centerZ - request.radius
            val maxZ = request.centerZ + request.radius
            val cells = ArrayList<Int>()
            for ((row, zs) in rows) {
                val x = anchorX + row * reach
                if (x !in minX..maxX) continue
                for (z in zs) {
                    if (z !in minZ..maxZ) continue
                    val cell = heightmap[column(x, z)]
                    if (cell >= 0) cells.add(cell)
                }
            }
            return cells
        }

        /**
         * The torch positions (z) of row [row], walking both ways from a lattice point next to the anchor. Without a
         * [previous] row only the row's own line needs light, otherwise the band between the two rows does.
         */
        private fun layRow(row: Int, previous: Pair<Int, IntArray>?, anchorX: Int, anchorZ: Int): IntArray {
            val x = anchorX + row * reach
            lit.fill(false)
            val band = if (previous == null) {
                x..x
            } else {
                val previousX = anchorX + previous.first * reach
                for (z in previous.second) light(previousX, z)
                if (previousX < x) previousX + 1..x else x..previousX - 1
            }

            // Lattice points in a row are twice the reach apart, every other row is shifted by the reach.
            val start = anchorZ + startIndex(anchorZ, snapshot.minZ, snapshot.maxZ, Math.floorMod(row, 2), 2) * reach
            val zs = IntArrayList()
            zs.add(start)
            light(x, start)
            var z = start
            while (z <= snapshot.maxZ) {
                z += step(x, z, 1, band)
                zs.add(z)
                light(x, z)
            }
            z = start
            while (z >= snapshot.minZ) {
                z -= step(x, z, -1, band)
                zs.add(z)
                light(x, z)
            }
            return zs.toArray()
        }

        /**
         * How far (in [direction]) the torch after the one at ([x], [from]) goes: the largest gap up to twice the reach
         * that leaves no floor in [band] between the two dark, or the one leaving the least dark floor right after
         * [from] if every gap does.
         */
        private fun step(x: Int, from: Int, direction: Int, band: IntRange): Int {
            val maxStep = 2 * reach
            val minStep = maxOf(1, reach)
            coverableStamp++
            for (s in minStep..maxStep) {
                for (column in litColumns(x, from + direction * s)) coverable[column] = coverableStamp
            }
            for (s in maxStep downTo minStep) {
                if (darkColumns(x, from, from + direction * s, band, from + direction * s) == 0) return s
            }
            // Ties go to the larger gap.
            return (maxStep downTo minStep).minBy { s ->
                darkColumns(x, from, from + direction * s, band, from + direction * minStep)
            }
        }

        /**
         * Floor columns in [band] from [from] to [to] that stay dark with a torch at ([x], [next]), not counting
         * the ones no candidate could light.
         */
        private fun darkColumns(x: Int, from: Int, next: Int, band: IntRange, to: Int): Int {
            candidateStamp++
            for (column in litColumns(x, next)) candidate[column] = candidateStamp
            var dark = 0
            for (bx in band) {
                if (bx !in snapshot.minX..snapshot.maxX) continue
                for (bz in minOf(from, to)..maxOf(from, to)) {
                    if (bz !in snapshot.minZ..snapshot.maxZ) continue
                    val column = column(bx, bz)
                    if (heightmap[column] < 0 || lit[column] || candidate[column] == candidateStamp) continue
                    if (coverable[column] == coverableStamp) dark++
                }
            }
            return dark
        }

        private fun light(x: Int, z: Int) {
            for (column in litColumns(x, z)) lit[column] = true
        }

        private fun litColumns(x: Int, z: Int): IntArray = litCache.getOrPut(key(x, z)) {
            val columns = IntArrayList()
            if (x in snapshot.minX..snapshot.maxX && z in snapshot.minZ..snapshot.maxZ) {
                val cell = heightmap[column(x, z)]
                if (cell >= 0) {
                    spreader.spread(cell, request.emission, request.emission - reach) { reached, _ ->
                        val column = reached / snapshot.sizeY
                        if (heightmap[column] == reached) columns.add(column)
                    }
                }
            } else {
                // Beyond the snapshot: as on flat ground.
                for (dx in -reach..reach) {
                    val rest = reach - abs(dx)
                    for (dz in -rest..rest) {
                        val cx = x + dx
                        val cz = z + dz
                        if (cx in snapshot.minX..snapshot.maxX && cz in snapshot.minZ..snapshot.maxZ) columns.add(column(cx, cz))
                    }
                }
            }
            columns.toArray()
        }

        private fun column(x: Int, z: Int) = (x - snapshot.minX) * sizeZ + (z - snapshot.minZ)

        private fun key(x: Int, z: Int) = x.toLong() shl 32 or (z.toLong() and 0xFFFFFFFFL)

        /**
         * The index k of the lattice line `anchor + k * reach` (with k ≡ [parity] mod [modulus]) to start from: next
         * to the anchor if it lies in [low]..[high], otherwise the last one before that range on the anchor's side,
         * as everything beyond the snapshot is laid out regularly anyway.
         */
        private fun startIndex(anchor: Int, low: Int, high: Int, parity: Int, modulus: Int): Int {
            val k = when {
                anchor < low -> Math.floorDiv(low - 1 - anchor, reach)
                anchor > high -> -Math.floorDiv(anchor - high - 1, reach)
                else -> 0
            }
            if (Math.floorMod(k - parity, modulus) == 0) return k
            return if (anchor > high) k + 1 else if (anchor < low) k - 1 else k + 1
        }
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
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun isEmpty() = size == 0
        fun toArray(): IntArray = data.copyOf(size)
    }
}
