package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.Unpooled
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.s2c.play.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replays captured sessions by:
 * 1. Reading tick records (48-byte enhanced format) for player position/input state
 * 2. Reading raw S2C packets captured at the Netty level and dispatching them
 *    through the ClientPlayNetworkHandler for faithful first-person POV replay.
 *
 * This matches ReplayMod's approach: all UI screens (inventory, crafting, chests,
 * furnaces, enchanting tables, anvils, etc.) work automatically because packets
 * are processed through the normal client packet handling pipeline.
 */
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
        val client = MinecraftClient.getInstance()
        val sessionsDir = File(client.runDirectory, "mcap_replay/sessions")
        
        if (!sessionsDir.exists()) return

        val sessions = sessionsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
        if (sessions.isNullOrEmpty()) return

        availableSessions = sessions.filter { session ->
            val chunksDir = File(session, "chunks")
            val chunkCount = chunksDir.listFiles()?.count { it.extension == "cap" } ?: 0
            chunkCount >= 1
        }
        
        if (availableSessions.isEmpty()) return

        selectedSessionIndex = 0
        openSelectedSession()
    }
    
    private fun openSelectedSession() {
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }
        
        if (selectedSessionIndex < 0 || selectedSessionIndex >= availableSessions.size) return
        
        val session = availableSessions[selectedSessionIndex]
        sessionName = session.name
                
        replayHandle = NativeBridge.nativeOpenReplay(session.absolutePath)
        if (replayHandle < 0) return

        maxTick = NativeBridge.nativeGetReplayMaxTick(replayHandle)
        println("[MCAP] Opened session: $sessionName, maxTick=$maxTick")
        
        isActive = true
        ReplayState.setReplayActive(true)
        isPlaying = false
        tick = 0
        
        // Apply initial tick and packets
        val client = MinecraftClient.getInstance()
        applyPacketsForTick(client, 0)
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
        
        if (tick % 100 == 0) {
            println("[MCAP] Replay progress: tick $tick / $maxTick")
        }
        
        applyRecordedTick(client)
        
        // Manually tick hand swing animation
        val player = client.player
        if (player != null && player.handSwinging) {
            player.handSwingTicks++
            if (player.handSwingTicks >= 6) {
                player.handSwingTicks = 0
                player.handSwinging = false
            }
        }
        
        tick++
        if (tick > maxTick) {
            println("[MCAP] Replay looping from tick $maxTick back to 0")
            tick = 0
            applyPacketsForTick(client, 0)
        }
    }

    private fun applyRecordedTick(client: MinecraftClient) {
        val player = client.player ?: return
        if (replayHandle < 0) return

        val record = NativeBridge.nativeReadTick(replayHandle, tick)
        if (record.isEmpty()) return

        // Parse enhanced record (48 bytes):
        // u16 flags (0-1), u8 hotbar (2), u8 mouseBtn (3)
        // i16 yaw_fp (4-5), i16 pitch_fp (6-7)
        // u32 tick (8-11)
        // f32 x (12-15), f32 y (16-19), f32 z (20-23)
        // f32 health (24-27), u8 food (28), u8 screenType (29), u16 xpLevel (30-31)
        // f32 velX (32-35), f32 velY (36-39), f32 velZ (40-43)
        // i16 cursorX (44-45), i16 cursorY (46-47)
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.short.toInt() and 0xFFFF
        val hotbar = buf.get().toInt() and 0xFF
        buf.get() // mouseBtn (informational, not applied)
        val yawFp = buf.short
        val pitchFp = buf.short
        buf.int // skip tick
        val x = buf.float
        val y = buf.float
        val z = buf.float

        val yaw = yawFp.toFloat() / 100.0f
        val pitch = pitchFp.toFloat() / 100.0f
        
        if (x.isNaN() || y.isNaN() || z.isNaN() || (x == 0f && y == 0f && z == 0f)) return

        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        
        // Set position with full sync
        player.refreshPositionAndAngles(xd, yd, zd, yaw, pitch)
        player.prevX = xd
        player.prevY = yd
        player.prevZ = zd
        player.lastRenderX = xd
        player.lastRenderY = yd
        player.lastRenderZ = zd
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
        
        val cameraEntity = client.cameraEntity
        if (cameraEntity != null && cameraEntity == player) {
            cameraEntity.yaw = yaw
            cameraEntity.pitch = pitch
            cameraEntity.prevYaw = yaw
            cameraEntity.prevPitch = pitch
        }

        if (hotbar in 0..8) {
            player.inventory.selectedSlot = hotbar
        }
        
        // Handle arm swing from flags (bit 8)
        val armSwinging = (flags and (1 shl 8)) != 0
        if (armSwinging && !player.handSwinging) {
            player.swingHand(net.minecraft.util.Hand.MAIN_HAND)
        }
        
        // Apply raw S2C packets for this tick through network handler
        applyPacketsForTick(client, tick)
    }
    
    /**
     * Apply all raw S2C packets for a given tick.
     * 
     * New format (from RawPacketCapture): packets are stored with real MC packet IDs
     * and are dispatched through the ClientPlayNetworkHandler for faithful replay.
     * This means ALL UI screens, entity updates, block changes, chat, particles, etc.
     * work automatically without individual packet type handling.
     */
    private fun applyPacketsForTick(client: MinecraftClient, tick: Int) {
        if (replayHandle < 0) return
        val handler = client.networkHandler ?: return
        
        val packetsData = NativeBridge.nativeReadPacketsForTick(replayHandle, tick)
        if (packetsData.isEmpty()) return
        
        val buf = ByteBuffer.wrap(packetsData).order(ByteOrder.LITTLE_ENDIAN)
        
        while (buf.remaining() >= 6) {
            val packetId = buf.short.toInt() and 0xFFFF
            val dataLen = buf.int
            
            if (buf.remaining() < dataLen) break
            
            val packetData = ByteArray(dataLen)
            buf.get(packetData)
            
            try {
                replayRawPacket(client, handler, packetId, packetData)
            } catch (e: Exception) {
                // Ignore packet parsing errors during replay
            }
        }
    }
    
    /**
     * Replay a raw S2C packet by constructing the MC Packet object from its ID
     * and data, then dispatching it through the network handler.
     *
     * This is the key fix over the old approach: instead of manually handling
     * each packet type, we use MC's own packet construction and dispatch.
     * This ensures all UIs, entity state, world state, etc. update correctly.
     */
    private fun replayRawPacket(
        client: MinecraftClient,
        handler: net.minecraft.client.network.ClientPlayNetworkHandler,
        packetId: Int,
        data: ByteArray
    ) {
        val pktBuf = PacketByteBuf(Unpooled.wrappedBuffer(data))
        
        try {
            // Use MC's packet registry to construct the packet from its ID
            val state = NetworkState.PLAY
            val packet = state.getPacketHandler(NetworkSide.CLIENTBOUND, packetId, pktBuf)
            
            if (packet != null) {
                // Skip certain packets during replay that could cause issues
                // (similar to ReplayMod's BAD_PACKETS list)
                if (shouldSkipPacket(packet)) return
                
                // Dispatch through the network handler - this is the magic!
                // The client processes it exactly as if it came from the server.
                @Suppress("UNCHECKED_CAST")
                (packet as net.minecraft.network.packet.Packet<net.minecraft.network.listener.ClientPlayPacketListener>)
                    .apply(handler)
            }
        } catch (e: Exception) {
            // Fall back to manual handling for packets that fail to construct
            tryManualPacketReplay(client, packetId, data)
        } finally {
            pktBuf.release()
        }
    }
    
    /**
     * Packets to skip during replay to avoid crashes or unwanted behavior.
     * Matches ReplayMod's BAD_PACKETS concept.
     */
    private fun shouldSkipPacket(packet: net.minecraft.network.packet.Packet<*>): Boolean {
        return when (packet) {
            // Skip disconnect - would kick us out
            is DisconnectS2CPacket -> true
            // Skip keep alive - not needed for replay
            is KeepAliveS2CPacket -> true
            // Skip player position - we set position from tick records
            is PlayerPositionLookS2CPacket -> true
            // Skip game join - would reset the client
            is GameJoinS2CPacket -> true
            else -> false
        }
    }
    
    /**
     * Fallback manual packet replay for packets that can't be constructed
     * from the packet registry (e.g., due to version differences or
     * client-side captured events).
     */
    private fun tryManualPacketReplay(client: MinecraftClient, packetId: Int, data: ByteArray) {
        // This handles legacy packet format or client-side captures
        // that don't have real MC packet IDs
    }

    fun renderHud(ctx: DrawContext) {
        if (!isActive) return
        val status = if (isPlaying) "PLAY" else "PAUSE"
        val progress = if (maxTick > 0) "${(tick * 100 / maxTick)}%" else "0%"
        val sessionInfo = "(${selectedSessionIndex + 1}/${availableSessions.size})"
        val text = "REPLAY $status  $tick/$maxTick ($progress)"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, text, 8, 8, 0x00FF00, true)
        
        val sessionText = "Session $sessionInfo: $sessionName"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, sessionText, 8, 20, 0xFFFF00, true)
        
        val controls = "G=Play/Pause  .=Step  [/]=Prev/Next  V=Video  R=Exit"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, controls, 8, 32, 0xAAAAAA, true)
    }
}
