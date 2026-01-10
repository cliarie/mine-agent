package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.Unpooled
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.s2c.play.*
import net.minecraft.screen.ScreenHandler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReplayController {
    var isActive: Boolean = false
        private set

    var isPlaying: Boolean = false
        private set
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
        ReplayState.setReplayActive(true)
        isPlaying = false
        tick = 0
        
        // Apply tick 0 packets to restore initial world state
        val client = MinecraftClient.getInstance()
        applyPacketsForTick(client, 0)
        println("[MCAP] Applied initial world state (tick 0 packets)")
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
        ReplayState.setReplayActive(false)
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
            // Restore initial world state when looping
            applyPacketsForTick(client, 0)
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
        
        // Debug: log yaw/pitch values
        if (tick % 20 == 0) {
            println("[MCAP] Replay tick $tick: yaw=$yaw, pitch=$pitch, pos=($x, $y, $z)")
        }

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
        player.bodyYaw = yaw
        player.prevYaw = yaw
        player.prevPitch = pitch
        player.prevHeadYaw = yaw
        player.prevBodyYaw = yaw

        val acc = player as EntityPrevAnglesAccessor
        acc.mcap_setPrevYaw(yaw)
        acc.mcap_setPrevPitch(pitch)
        
        // Also update the camera entity if it's the player
        val cameraEntity = client.cameraEntity
        if (cameraEntity != null && cameraEntity == player) {
            cameraEntity.yaw = yaw
            cameraEntity.pitch = pitch
            cameraEntity.prevYaw = yaw
            cameraEntity.prevPitch = pitch
        }

        // Apply hotbar
        if (hotbar in 0..8) {
            player.inventory.selectedSlot = hotbar
        }
        
        // Apply packets for this tick
        applyPacketsForTick(client, tick)
    }
    
    // Packet type IDs (must match PacketCaptureMixin.java)
    companion object {
        private const val PKT_SCREEN_HANDLER_SLOT = 1
        private const val PKT_INVENTORY = 2
        private const val PKT_OPEN_SCREEN = 3
        private const val PKT_CLOSE_SCREEN = 4
        private const val PKT_PLAYER_POSITION = 5
        private const val PKT_ENTITY_POSITION = 6
        private const val PKT_BLOCK_UPDATE = 7
        private const val PKT_HELD_ITEM_CHANGE = 8
        private const val PKT_HEALTH_UPDATE = 11
        private const val PKT_EXPERIENCE_UPDATE = 12
        private const val PKT_WORLD_TIME = 13
    }
    
    private fun applyPacketsForTick(client: MinecraftClient, tick: Int) {
        if (replayHandle < 0) return
        
        val packetsData = NativeBridge.nativeReadPacketsForTick(replayHandle, tick)
        if (packetsData.isEmpty()) return
        
        val buf = ByteBuffer.wrap(packetsData).order(ByteOrder.LITTLE_ENDIAN)
        
        while (buf.remaining() >= 4) {
            val packetId = buf.short.toInt() and 0xFFFF
            val dataLen = buf.short.toInt() and 0xFFFF
            
            if (buf.remaining() < dataLen) break
            
            val packetData = ByteArray(dataLen)
            buf.get(packetData)
            
            try {
                applyPacket(client, packetId, packetData)
            } catch (e: Exception) {
                // Ignore packet parsing errors during replay
            }
        }
    }
    
    private fun applyPacket(client: MinecraftClient, packetId: Int, data: ByteArray) {
        val player = client.player ?: return
        val pktBuf = PacketByteBuf(Unpooled.wrappedBuffer(data))
        
        when (packetId) {
            PKT_SCREEN_HANDLER_SLOT -> {
                // Slot update in current screen handler
                val packet = ScreenHandlerSlotUpdateS2CPacket(pktBuf)
                val syncId = packet.syncId
                val slot = packet.slot
                val stack = packet.itemStack
                
                // Apply to player's current screen handler if syncId matches
                val handler = player.currentScreenHandler
                if (handler != null && handler.syncId == syncId && slot >= 0 && slot < handler.slots.size) {
                    handler.slots[slot].stack = stack
                }
            }
            
            PKT_INVENTORY -> {
                // Full inventory sync
                val packet = InventoryS2CPacket(pktBuf)
                val syncId = packet.syncId
                val stacks = packet.contents
                
                val handler = player.currentScreenHandler
                if (handler != null && handler.syncId == syncId) {
                    for ((index, stack) in stacks.withIndex()) {
                        if (index < handler.slots.size) {
                            handler.slots[index].stack = stack
                        }
                    }
                }
            }
            
            PKT_HELD_ITEM_CHANGE -> {
                // Server-side hotbar slot selection
                val packet = UpdateSelectedSlotS2CPacket(pktBuf)
                val slot = packet.slot
                if (slot in 0..8) {
                    player.inventory.selectedSlot = slot
                }
            }
            
            PKT_OPEN_SCREEN -> {
                // For now, just log - opening screens requires more complex handling
                println("[MCAP] OpenScreen packet at tick $tick")
            }
            
            PKT_CLOSE_SCREEN -> {
                // Close current screen
                client.setScreen(null)
            }
            
            PKT_PLAYER_POSITION -> {
                // Server position correction - we handle position in tick data
            }
            
            PKT_ENTITY_POSITION -> {
                // Entity positions - would need entity tracking to apply
            }
            
            PKT_BLOCK_UPDATE -> {
                // Block updates (restore blocks, cactus, etc.)
                val packet = BlockUpdateS2CPacket(pktBuf)
                val pos = packet.pos
                val state = packet.state
                val world = client.world
                if (world != null) {
                    world.setBlockState(pos, state, 3) // 3 = send to clients
                }
            }
            
            PKT_HEALTH_UPDATE -> {
                // Health, food, saturation update
                val packet = HealthUpdateS2CPacket(pktBuf)
                player.health = packet.health
                player.hungerManager.foodLevel = packet.food
                player.hungerManager.saturationLevel = packet.saturation
            }
            
            PKT_EXPERIENCE_UPDATE -> {
                // Experience update
                val packet = ExperienceBarUpdateS2CPacket(pktBuf)
                player.experienceProgress = packet.barProgress
                player.totalExperience = packet.experienceLevel // approximation
                player.experienceLevel = packet.experienceLevel
            }
            
            PKT_WORLD_TIME -> {
                // World time update (day/night cycle)
                val packet = WorldTimeUpdateS2CPacket(pktBuf)
                val world = client.world
                if (world != null) {
                    world.timeOfDay = packet.time
                }
            }
        }
        
        pktBuf.release()
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
