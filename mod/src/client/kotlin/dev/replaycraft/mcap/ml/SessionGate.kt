package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient

/**
 * Gates the ML data export pipeline.
 * Only allows capture in survival mode with no screen open at session start.
 */
object SessionGate {

    enum class GateResult {
        /** All checks pass — start ML capture */
        ALLOW,
        /** Definitive rejection (creative/spectator) — do not retry */
        DENY_PERMANENT,
        /** Transient rejection (screen open, player null) — retry next tick */
        DENY_TRANSIENT
    }

    /**
     * Check whether ML capture should be enabled for the current session.
     */
    fun checkGate(client: MinecraftClient): GateResult {
        val player = client.player ?: return GateResult.DENY_TRANSIENT

        if (player.abilities.creativeMode) return GateResult.DENY_PERMANENT
        if (player.isSpectator) return GateResult.DENY_PERMANENT
        if (client.currentScreen != null) return GateResult.DENY_TRANSIENT

        return GateResult.ALLOW
    }
}
