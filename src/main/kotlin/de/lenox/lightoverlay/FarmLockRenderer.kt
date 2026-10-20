package de.lenox.lightoverlay

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector4f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
//? if >=26.3 {
/*import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.BindGroupLayouts
import java.util.Optional
*///?} elif >=26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.framegraph.FrameGraphBuilder
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.LevelTargetBundle
import java.util.Optional
*///?} elif >=26.1 {
/*import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
import java.util.OptionalInt
*///?} else {
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.VertexFormat
import java.util.OptionalInt
//?}

/**
 * Draws the [FarmLock] spheres like MiniHUD's blocky spawn/despawn spheres: the outer faces of the floor blocks a mob
 * standing on could be inside the sphere ([FarmLock.Sphere.contains] one block up), with coplanar faces merged into as
 * few rectangles as possible. Faces lie exactly on the block grid and the whole sphere is drawn. Plus a marker on the
 * locked spot.
 *
 * Like MiniHUD, the sphere is uploaded to a GPU buffer once and drawn from there every frame in its own render pass
 * with its own pipeline (`debugQuads` with a depth bias, so faces on terrain faces don't z-fight). Before 26.2 that
 * happens in the render hook; from 26.2 on the hooks only collect submits, so `MixinLevelRenderer` calls in instead.
 */
object FarmLockRenderer {
    /** MiniHUD's default sphere alpha (`#60A04050`). */
    private const val FACE_ALPHA = 0x60

    /** The marker on the locked spot is a player-sized box. */
    private const val MARKER_HALF_WIDTH = 0.3f
    private const val MARKER_HEIGHT = 1.8f
    private const val MARKER_ALPHA = 0x50

    /** A voxel sphere around ([x], [y], [z]) with the given [radius], measured like [FarmLock.Sphere.contains]. */
    private data class Key(val x: Double, val y: Double, val z: Double, val radius: Int)

    /** Quads as 4 corners × xyz in [vertices], relative to the block ([ox], [oy], [oz]) so the floats stay exact. */
    private class Mesh(val ox: Int, val oy: Int, val oz: Int, vertices: FloatArray) {
        val indexCount = vertices.size / 12 * 6

        // POSITION_COLOR vertices in white (the colour is the ColorModulator), so the colour can change without a
        // new upload. Built along with the mesh, off the render thread, and dropped once uploaded.
        var data: ByteBuffer? = ByteBuffer.allocateDirect(vertices.size / 3 * 16).order(ByteOrder.nativeOrder()).apply {
            var i = 0
            while (i < vertices.size) {
                putFloat(vertices[i]).putFloat(vertices[i + 1]).putFloat(vertices[i + 2]).putInt(-1)
                i += 3
            }
            flip()
        }

        // Uploaded on the first draw. Render thread only.
        var buffer: GpuBuffer? = null
    }

    /** Meshes for the keys last requested, built on [executor]. */
    @Volatile
    private var meshes: Map<Key, Mesh> = emptyMap()

    /** Render thread only. */
    private var requested: List<Key> = emptyList()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Light Overlay sphere mesher").apply { isDaemon = true }
    }

    /**
     * Draws the marker into [quads], and before 26.2 also the spheres. Vertex positions are made camera-relative, so
     * [pose] only needs the camera rotation.
     */
    fun draw(quads: VertexConsumer, pose: PoseStack.Pose, sphere: FarmLock.Sphere, cameraPos: Vec3) {
        //? if <26.2 {
        val modelView = Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose())
        drawSpheres(sphere, cameraPos, Minecraft.getInstance().mainRenderTarget, modelView)
        //?}
        drawMarker(
            quads, pose, (MARKER_ALPHA shl 24) or LightOverlayConfig.sphereColor,
            (sphere.x - cameraPos.x).toFloat(), (sphere.y - cameraPos.y).toFloat(), (sphere.z - cameraPos.z).toFloat()
        )
    }

    //? if >=26.3 {
    /*/**
     * Draws the spheres onto the main target. Called by `MixinLevelRenderer` from inside the main pass, after the
     * translucent terrain and features, while the camera rotation is on the model-view stack and the depth buffer
     * hasn't been cleared for the always-on-top features yet. (MiniHUD draws once `LevelRenderer.render` returns.)
     */
    @JvmStatic
    fun renderLevel() {
        val sphere = visibleSphere() ?: return
        val mc = Minecraft.getInstance()
        drawSpheres(sphere, cameraPos(mc), mc.gameRenderer.mainRenderTarget(), RenderSystem.getModelViewMatrixCopy())
    }
    *///?} elif >=26.2 {
    /*/**
     * Adds a frame pass that draws the spheres onto the main target, like MiniHUD's world-last pass. Called by
     * `MixinLevelRenderer` right before the always-on-top pass, which clears the depth buffer.
     */
    @JvmStatic
    fun addPass(frame: FrameGraphBuilder, targets: LevelTargetBundle) {
        val sphere = visibleSphere() ?: return
        val cameraPos = cameraPos(Minecraft.getInstance())
        val pass = frame.addPass("${LightOverlayMod.MOD_ID}_farm_lock")
        targets.main = pass.readsAndWrites(targets.main)
        val main = targets.main
        pass.disableCulling()
        pass.executes { drawSpheres(sphere, cameraPos, main.get(), RenderSystem.getModelViewMatrixCopy()) }
    }
    *///?}

    //? if >=26.2 {
    /*private fun visibleSphere(): FarmLock.Sphere? =
        FarmLock.sphere?.takeIf { LightOverlayMod.active && LightOverlayConfig.showLockSphere }

    private fun cameraPos(mc: Minecraft): Vec3 = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos
    *///?}

    /** Draws the meshes of [sphere] (and its inner sphere, if shown), building them in the background first. */
    private fun drawSpheres(sphere: FarmLock.Sphere, cameraPos: Vec3, target: RenderTarget, modelView: Matrix4fc) {
        val outer = Key(sphere.x, sphere.y, sphere.z, sphere.radius)
        val inner = Key(sphere.x, sphere.y, sphere.z, sphere.innerRadius)
            .takeIf { LightOverlayConfig.showLockInnerSphere && sphere.innerRadius > 0 }
        val current = meshes
        meshes(listOfNotNull(outer, inner))
        release(current.values)
        val rgb = LightOverlayConfig.sphereColor
        current[outer]?.let { drawBuffer(it, cameraPos, target, modelView, rgb) }
        inner?.let { current[it] }?.let { drawBuffer(it, cameraPos, target, modelView, rgb) }
    }

    /** Builds the meshes for [keys] in the background if they aren't there yet, and forgets all others. */
    private fun meshes(keys: List<Key>) {
        if (keys == requested) return
        requested = keys
        // Tasks run one after another, so the last request wins.
        executor.execute {
            val built = meshes
            meshes = keys.associateWith { built[it] ?: build(it) }
        }
    }

    /** MiniHUD's `MINIHUD_SHAPE_OFFSET_NO_CULL`: vanilla `debugQuads`, plus the depth bias. */
    private val PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(LightOverlayMod.MOD_ID, "pipeline/farm_lock_sphere"))
        //? if >=26.3 {
        /*.withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        *///?} elif >=26.2 {
        /*.withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
        *///?} else {
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        //?}
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        //? if >=26.2 {
        /*// The depth buffer is reversed from 26.2 on (near is 1), so the test and the bias flip too.
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 3f, 3f))
        .withCull(false)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        *///?} elif >=26.1 {
        /*.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, -3f, -3f))
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        *///?} else {
        .withBlend(BlendFunction.TRANSLUCENT)
        .withDepthWrite(false)
        .withDepthBias(-3f, -3f)
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        //?}
        .build()

    /** Meshes with a GPU buffer. Render thread only. */
    private val uploaded = ArrayList<Mesh>()

    /** Frees the GPU buffers of meshes that aren't in [kept] any more. */
    private fun release(kept: Collection<Mesh>) {
        uploaded.removeIf { mesh ->
            if (mesh in kept) return@removeIf false
            mesh.buffer?.close()
            mesh.buffer = null
            true
        }
    }

    /**
     * Draws [mesh] from its GPU buffer (uploading it first if needed) straight into [target], like MiniHUD's
     * `RenderObjectVbo`. All faces have the same colour, so the translucent quads blend the same in any order and need
     * no sorting.
     */
    private fun drawBuffer(mesh: Mesh, cameraPos: Vec3, target: RenderTarget, modelView: Matrix4fc, rgb: Int) {
        if (mesh.indexCount == 0) return
        val device = RenderSystem.getDevice()
        val buffer = mesh.buffer ?: device.createBuffer(
            { "Light Overlay farm lock sphere" }, GpuBuffer.USAGE_VERTEX, mesh.data ?: return
        ).also {
            mesh.buffer = it
            mesh.data = null
            uploaded += mesh
        }

        val colorView = target.colorTextureView ?: return
        //? if >=26.3 {
        /*val depthView = if (target.hasDepth()) target.depthTextureView else null
        *///?} else {
        val depthView = if (target.useDepth) target.depthTextureView else null
        //?}
        val transform = Matrix4f(modelView).translate(
            (mesh.ox - cameraPos.x).toFloat(), (mesh.oy - cameraPos.y).toFloat(), (mesh.oz - cameraPos.z).toFloat()
        )
        val colorModulator = Vector4f(
            (rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f, FACE_ALPHA / 255f
        )
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(transform, colorModulator, Vector3f(), Matrix4f())
        //? if >=26.2 {
        /*val indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val noClear = Optional.empty<org.joml.Vector4fc>()
        *///?} else {
        val indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
        val noClear = OptionalInt.empty()
        //?}
        val indexBuffer = indices.getBuffer(mesh.indexCount)

        device.createCommandEncoder().createRenderPass(
            { "Light Overlay farm lock sphere" }, colorView, noClear, depthView, OptionalDouble.empty()
        ).use { pass ->
            //? if >=26.3 {
            /*pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE))
            *///?} else {
            pass.setPipeline(PIPELINE)
            //?}
            val scissor = RenderSystem.getScissorStateForRenderTypeDraws()
            if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height())
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setIndexBuffer(indexBuffer, indices.type())
            //? if >=26.2 {
            /*pass.setVertexBuffer(0, buffer.slice())
            pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0)
            *///?} else {
            pass.setVertexBuffer(0, buffer)
            pass.drawIndexed(0, 0, mesh.indexCount, 1)
            //?}
        }
    }

    private const val NONE = Int.MIN_VALUE

    private fun build(key: Key): Mesh {
        val ox = floor(key.x).toInt()
        // MiniHUD draws the floor blocks under the positions that count, so the voxels sit one block below the feet.
        val oy = floor(key.y).toInt() - 1
        val oz = floor(key.z).toInt()
        val fx = key.x - ox
        val fy = key.y - (oy + 1)
        val fz = key.z - oz
        val r = key.radius
        // Columns -r-1..r+1 around the origin block, so every face has an empty neighbour inside the grid. The sphere
        // is convex, so each column is inside over one run of blocks [lo, hi] (relative y, empty if lo > hi).
        val n = 2 * r + 3
        val lo = IntArray(n * n) { Int.MAX_VALUE }
        val hi = IntArray(n * n) { Int.MIN_VALUE }
        for (i in 0 until n) {
            val dx = i - r - 1 + 0.5 - fx
            for (j in 0 until n) {
                val dz = j - r - 1 + 0.5 - fz
                val s = r.toDouble() * r - dx * dx - dz * dz
                if (s < 0) continue
                // Feet one block above the floor block y: (y - fy)² <= s.
                val root = sqrt(s)
                val l = ceil(fy - root).toInt()
                val h = floor(fy + root).toInt()
                if (l > h) continue
                lo[i * n + j] = l
                hi[i * n + j] = h
            }
        }

        val out = FloatList()
        val grid = IntArray(n * n)
        val off = r + 1

        // Tops and bottoms: one each per column, merged where neighbouring columns end at the same height.
        for (c in 0 until n * n) grid[c] = if (lo[c] <= hi[c]) hi[c] + 1 else NONE
        greedy(grid, n, n) { j0, i0, j1, i1, y ->
            out.quadY(y.toFloat(), (i0 - off).toFloat(), (j0 - off).toFloat(), (i1 - off).toFloat(), (j1 - off).toFloat())
        }
        for (c in 0 until n * n) grid[c] = if (lo[c] <= hi[c]) lo[c] else NONE
        greedy(grid, n, n) { j0, i0, j1, i1, y ->
            out.quadY(y.toFloat(), (i0 - off).toFloat(), (j0 - off).toFloat(), (i1 - off).toFloat(), (j1 - off).toFloat())
        }

        // Sides: for every plane between two rows of columns, the blocks inside on one side and outside on the other.
        // 1 marks faces pointing towards +X/+Z, 2 towards -X/-Z (kept apart so they merge separately); the grid is
        // (along the plane) × (relative y + off).
        for (axisX in booleanArrayOf(true, false)) {
            for (p in 0 until n - 1) {
                grid.fill(NONE)
                for (q in 0 until n) {
                    val a = if (axisX) p * n + q else q * n + p
                    val b = if (axisX) (p + 1) * n + q else q * n + p + 1
                    markDifference(grid, n, q, off, lo[a], hi[a], lo[b], hi[b], 1)
                    markDifference(grid, n, q, off, lo[b], hi[b], lo[a], hi[a], 2)
                }
                val plane = (p + 1 - off).toFloat()
                greedy(grid, n, n) { q0, y0, q1, y1, _ ->
                    val u0 = (q0 - off).toFloat(); val u1 = (q1 - off).toFloat()
                    val v0 = (y0 - off).toFloat(); val v1 = (y1 - off).toFloat()
                    if (axisX) out.quadX(plane, u0, v0, u1, v1) else out.quadZ(plane, u0, v0, u1, v1)
                }
            }
        }
        return Mesh(ox, oy, oz, out.toArray())
    }

    /** Marks the blocks of run [a0]..[a1] that aren't in run [b0]..[b1] in column [q] of the (q, y + [off]) [grid]. */
    private fun markDifference(grid: IntArray, n: Int, q: Int, off: Int, a0: Int, a1: Int, b0: Int, b1: Int, value: Int) {
        if (a0 > a1) return
        if (b0 > b1) {
            for (y in a0..a1) grid[(y + off) * n + q] = value
            return
        }
        for (y in a0..minOf(a1, b0 - 1)) grid[(y + off) * n + q] = value
        for (y in maxOf(a0, b1 + 1)..a1) grid[(y + off) * n + q] = value
    }

    /**
     * Greedy meshing: covers the cells of [grid] ([w] × [h], row-major) that aren't [NONE] with rectangles of equal
     * values, [emit]ted as (u0, v0, u1, v1, value) with exclusive ends. Clears the grid.
     */
    private inline fun greedy(grid: IntArray, w: Int, h: Int, emit: (Int, Int, Int, Int, Int) -> Unit) {
        for (v in 0 until h) {
            var u = 0
            while (u < w) {
                val value = grid[v * w + u]
                if (value == NONE) {
                    u++
                    continue
                }
                var u1 = u + 1
                while (u1 < w && grid[v * w + u1] == value) u1++
                var v1 = v + 1
                grow@ while (v1 < h) {
                    for (x in u until u1) if (grid[v1 * w + x] != value) break@grow
                    v1++
                }
                for (y in v until v1) grid.fill(NONE, y * w + u, y * w + u1)
                emit(u, v, u1, v1, value)
                u = u1
            }
        }
    }

    private class FloatList(capacity: Int = 1 shl 12) {
        private var data = FloatArray(capacity)
        private var size = 0

        private fun add(x: Float, y: Float, z: Float) {
            if (size + 3 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
            data[size++] = z
        }

        fun quadY(y: Float, x0: Float, z0: Float, x1: Float, z1: Float) {
            add(x0, y, z0); add(x1, y, z0); add(x1, y, z1); add(x0, y, z1)
        }

        fun quadX(x: Float, z0: Float, y0: Float, z1: Float, y1: Float) {
            add(x, y0, z0); add(x, y0, z1); add(x, y1, z1); add(x, y1, z0)
        }

        fun quadZ(z: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            add(x0, y0, z); add(x1, y0, z); add(x1, y1, z); add(x0, y1, z)
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** A translucent player-sized box standing on ([cx], [y], [cz]). Culling is off for debug quads. */
    private fun drawMarker(quads: VertexConsumer, pose: PoseStack.Pose, argb: Int, cx: Float, y: Float, cz: Float) {
        val w = MARKER_HALF_WIDTH
        val x1 = cx - w; val x2 = cx + w
        val z1 = cz - w; val z2 = cz + w
        val y2 = y + MARKER_HEIGHT
        side(quads, pose, argb, x1, z1, x2, z1, y, y2)
        side(quads, pose, argb, x2, z1, x2, z2, y, y2)
        side(quads, pose, argb, x2, z2, x1, z2, y, y2)
        side(quads, pose, argb, x1, z2, x1, z1, y, y2)
    }

    private fun side(
        quads: VertexConsumer, pose: PoseStack.Pose, argb: Int,
        x1: Float, z1: Float, x2: Float, z2: Float, y1: Float, y2: Float
    ) {
        quads.addVertex(pose, x1, y1, z1).setColor(argb)
        quads.addVertex(pose, x2, y1, z2).setColor(argb)
        quads.addVertex(pose, x2, y2, z2).setColor(argb)
        quads.addVertex(pose, x1, y2, z1).setColor(argb)
    }
}
