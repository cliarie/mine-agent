package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient

/**
 * Gates the ML data export pipeline.
 * Only allows capture in survival mode with no screen open at session start.
 */
object SessionGate {

    /**
     * Check whether ML capture should be enabled for the current session.
     * Returns true only if:
     * - player is not in creative mode
     * - player is not a spectator
     * - no screen is currently open
     */
    fun shouldCapture(client: MinecraftClient): Boolean {
        val player = client.player ?: return false

        if (player.abilities.creativeMode) return false
        if (player.isSpectator) return false
        if (client.currentScreen != null) return false

        return true
    }
}
