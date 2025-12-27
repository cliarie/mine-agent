package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
import dev.replaycraft.mcap.native.NativeBridge
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayController {
    var isActive: Boolean = false
        private set

    private var isPlaying: Boolean = false
    private var tick: Int = 0
    private var maxTick: Int = 0
    private var replayHandle: Long = -1
    private var sessionName: String = ""
    
    // Session picker state
    private var availableSessions: List<File> = emptyList()
    private var selectedSessionIndex: Int = 0

    fun start() {
        println("[MCAP] ReplayController.start() called")
        val client = MinecraftClient.getInstance()
        val sessionsDir = File(client.runDirectory, "mcap_replay/sessions")
        
        if (!sessionsDir.exists()) {
            println("[MCAP] No sessions directory found")
            return
        }

        // Find all sessions with actual data
        val sessions = sessionsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
        if (sessions.isNullOrEmpty()) {
            println("[MCAP] No replay sessions found")
            return
        }

        // Filter to sessions with at least 1 chunk
        availableSessions = sessions.filter { session ->
            val chunksDir = File(session, "chunks")
            val chunkCount = chunksDir.listFiles()?.count { it.extension == "cap" } ?: 0
            chunkCount >= 1
        }
        
        if (availableSessions.isEmpty()) {
            println("[MCAP] No session with recorded data found")
            return
        }

        println("[MCAP] Found ${availableSessions.size} session(s) with data")
        selectedSessionIndex = 0
        openSelectedSession()
    }
    
    private fun openSelectedSession() {
        // Close existing session if open
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }
        
        if (selectedSessionIndex < 0 || selectedSessionIndex >= availableSessions.size) {
            return
        }
        
        val session = availableSessions[selectedSessionIndex]
        sessionName = session.name
        println("[MCAP] Opening session: ${session.absolutePath}")
        
        replayHandle = NativeBridge.nativeOpenReplay(session.absolutePath)
        if (replayHandle < 0) {
            println("[MCAP] Failed to open replay: ${session.absolutePath}")
            return
        }

        maxTick = NativeBridge.nativeGetReplayMaxTick(replayHandle)
        println("[MCAP] Opened replay: $sessionName, maxTick=$maxTick")

        isActive = true
        isPlaying = false
        tick = 0
    }
    
    fun nextSession() {
        if (!isActive || availableSessions.isEmpty()) return
        selectedSessionIndex = (selectedSessionIndex + 1) % availableSessions.size
        openSelectedSession()
    }
    
    fun prevSession() {
        if (!isActive || availableSessions.isEmpty()) return
        selectedSessionIndex = (selectedSessionIndex - 1 + availableSessions.size) % availableSessions.size
        openSelectedSession()
    }

    fun stop() {
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }
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
        if (tick > maxTick) tick = maxTick
    }

    fun onClientTick(client: MinecraftClient) {
        if (!isActive) return
        if (!isPlaying) return
        applyRecordedTick(client)
        tick++
        if (tick > maxTick) {
            tick = 0 // Loop replay
        }
    }

    private fun applyRecordedTick(client: MinecraftClient) {
        val player = client.player ?: return
        if (replayHandle < 0) {
            println("[MCAP] applyRecordedTick: invalid handle")
            return
        }

        val record = NativeBridge.nativeReadTick(replayHandle, tick)
        if (record.isEmpty()) {
            println("[MCAP] applyRecordedTick: empty record for tick $tick")
            return
        }

        // Parse record (28 bytes):
        // u16 flags (0-1), u8 hotbar (2), u8 pad (3)
        // i16 yaw_fp (4-5), i16 pitch_fp (6-7)
        // u32 tick (8-11)
        // f32 x (12-15), f32 y (16-19), f32 z (20-23)
        // u32 pad2 (24-27)
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.short.toInt() and 0xFFFF
        val hotbar = buf.get().toInt() and 0xFF
        buf.get() // skip pad
        val yawFp = buf.short
        val pitchFp = buf.short
        buf.int // skip tick
        val x = buf.float
        val y = buf.float
        val z = buf.float

        val yaw = yawFp.toFloat() / 100.0f
        val pitch = pitchFp.toFloat() / 100.0f

        // Teleport player to recorded position (use refreshPositionAndAngles for proper sync)
        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        
        // Set position with full sync (prevents rubber-banding)
        player.refreshPositionAndAngles(xd, yd, zd, yaw, pitch)
        
        // Also set previous position to prevent interpolation flashing
        player.prevX = xd
        player.prevY = yd
        player.prevZ = zd
        player.lastRenderX = xd
        player.lastRenderY = yd
        player.lastRenderZ = zd
        
        // Zero velocity to stop momentum
        player.setVelocity(0.0, 0.0, 0.0)
        
        // Apply camera angles
        player.yaw = yaw
        player.pitch = pitch
        player.headYaw = yaw

        val acc = player as EntityPrevAnglesAccessor
        acc.mcap_setPrevYaw(yaw)
        acc.mcap_setPrevPitch(pitch)

        // Apply hotbar
        if (hotbar in 0..8) {
            player.inventory.selectedSlot = hotbar
        }
    }

    fun renderHud(ctx: DrawContext) {
        if (!isActive) return
        val status = if (isPlaying) "▶ PLAY" else "⏸ PAUSE"
        val progress = if (maxTick > 0) "${(tick * 100 / maxTick)}%" else "0%"
        val sessionInfo = "(${selectedSessionIndex + 1}/${availableSessions.size})"
        val text = "REPLAY $status  $tick/$maxTick ($progress)"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, text, 8, 8, 0x00FF00, true)
        
        // Session info
        val sessionText = "Session $sessionInfo: $sessionName"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, sessionText, 8, 20, 0xFFFF00, true)
        
        // Controls hint
        val controls = "G=Play/Pause  .=Step  [/]=Prev/Next Session  R=Exit"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, controls, 8, 32, 0xAAAAAA, true)
    }
}
