package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

class ReplayController {
    var isActive: Boolean = false
        private set

    private var isPlaying: Boolean = false
    private var tick: Int = 0

    fun start() {
        isActive = true
        isPlaying = false
        tick = 0
    }

    fun stop() {
        isActive = false
        isPlaying = false
    }

    fun togglePlayPause() {
        if (!isActive) return
        isPlaying = !isPlaying
    }

    fun stepOneTick(client: MinecraftClient) {
        if (!isActive) return
        if (isPlaying) return
        applyRecordedTick(client)
        tick++
    }

    fun onClientTick(client: MinecraftClient) {
        if (!isActive) return
        if (!isPlaying) return
        applyRecordedTick(client)
        tick++
    }

    private fun applyRecordedTick(client: MinecraftClient) {
        val player = client.player ?: return

        // TODO: Replace with decoded tick stream. For now, just demonstrate camera forcing.
        val yaw = player.yaw
        val pitch = player.pitch

        player.yaw = yaw
        player.pitch = pitch

        val acc = player as EntityPrevAnglesAccessor
        acc.mcap_setPrevYaw(yaw)
        acc.mcap_setPrevPitch(pitch)

        // TODO: keybind + click edge injection goes here once replay stream is wired.
    }

    fun renderHud(ctx: DrawContext) {
        if (!isActive) return
        val text = "REPLAY ${if (isPlaying) "PLAY" else "PAUSE"}  tick=$tick"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, text, 8, 8, 0xFFFFFF, true)
    }
}
