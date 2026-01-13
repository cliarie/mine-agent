package dev.replaycraft.mcap.capture

import io.netty.buffer.Unpooled
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket
import net.minecraft.util.math.BlockPos

/**
 * Captures the initial world state (blocks around player) at the start of recording.
 * These are stored as tick 0 packets so they get replayed first to restore the world.
 */
object InitialWorldCapture {
    
    private const val PKT_BLOCK_UPDATE = 7
    private const val CAPTURE_RADIUS = 16 // Blocks in each direction (33^3 cube)
    
    /**
     * Capture all blocks in a cube around the player and queue them as tick 0 packets.
     * This should be called when recording starts.
     */
    fun captureInitialBlocks() {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val world = client.world ?: return
        
        val centerX = player.blockPos.x
        val centerY = player.blockPos.y
        val centerZ = player.blockPos.z
        
        var capturedCount = 0
        
        // Capture blocks in a cube around the player
        for (dx in -CAPTURE_RADIUS..CAPTURE_RADIUS) {
            for (dy in -CAPTURE_RADIUS..CAPTURE_RADIUS) {
                for (dz in -CAPTURE_RADIUS..CAPTURE_RADIUS) {
                    val pos = BlockPos(centerX + dx, centerY + dy, centerZ + dz)
                    
                    // Only capture if chunk is loaded
                    if (!world.isChunkLoaded(pos)) continue
                    
                    val state = world.getBlockState(pos)
                    
                    // Skip air blocks to save space (they're the default)
                    if (state.isAir) continue
                    
                    // Serialize as BlockUpdateS2CPacket
                    try {
                        val packet = BlockUpdateS2CPacket(pos, state)
                        val buf = PacketByteBuf(Unpooled.buffer())
                        packet.write(buf)
                        val data = ByteArray(buf.readableBytes())
                        buf.readBytes(data)
                        buf.release()
                        
                        // Queue as tick 0 packet (will be first to replay)
                        PacketCapture.captureInitialPacket(PKT_BLOCK_UPDATE, data)
                        capturedCount++
                    } catch (e: Exception) {
                        // Ignore serialization errors
                    }
                }
            }
        }
        
            }
}
