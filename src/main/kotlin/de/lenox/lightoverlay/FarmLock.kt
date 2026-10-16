package de.lenox.lightoverlay

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component

/**
 * Farm mode: locks the overlay to a fixed player position (e.g. the AFK spot of a mob farm). Hostile mobs only spawn
 * between [Sphere.innerRadius] (no spawns closer to a player) and [Sphere.radius] (instant despawn further away), so
 * only the floor in that shell needs light; everything else is shown in [LightOverlayConfig.outsideColor].
 */
object FarmLock {
    /** The spawn shell around the locked position ([x], [y], [z]: the player's feet). */
    data class Sphere(val x: Double, val y: Double, val z: Double, val radius: Int, val innerRadius: Int) {
        /** Whether a mob standing in the block at ([bx], [by], [bz]) could spawn, measured like the vanilla spawner. */
        fun contains(bx: Int, by: Int, bz: Int): Boolean {
            val dx = bx + 0.5 - x
            val dy = by - y
            val dz = bz + 0.5 - z
            val distanceSq = dx * dx + dy * dy + dz * dz
            return distanceSq > innerRadius.toDouble() * innerRadius && distanceSq <= radius.toDouble() * radius
        }
    }

    /** The current lock with the configured radii, or null if not locked. Refreshed every tick. */
    @Volatile
    var sphere: Sphere? = null
        private set

    private var position: DoubleArray? = null
    private var lockedLevel: ClientLevel? = null

    val locked: Boolean get() = position != null

    fun tick(mc: Minecraft) {
        // The lock belongs to one world and dimension.
        if (position != null && mc.level !== lockedLevel) unlock()
        sphere = position?.let { (x, y, z) ->
            Sphere(x, y, z, LightOverlayConfig.lockRadius, minOf(LightOverlayConfig.lockInnerRadius, LightOverlayConfig.lockRadius))
        }
    }

    /** Locks onto the player's current position, or unlocks if already locked. */
    fun toggle(mc: Minecraft): Component {
        val player = mc.player ?: return Component.empty()
        if (position != null) {
            unlock()
            return Component.translatable("lightoverlay.message.unlocked")
        }
        position = doubleArrayOf(player.x, player.y, player.z)
        lockedLevel = mc.level
        tick(mc)
        return Component.translatable(
            "lightoverlay.message.locked", player.blockX, player.blockY, player.blockZ, LightOverlayConfig.lockRadius
        )
    }

    private fun unlock() {
        position = null
        lockedLevel = null
        sphere = null
    }
}
