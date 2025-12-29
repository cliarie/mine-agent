package dev.replaycraft.mcap.capture

import net.minecraft.network.packet.Packet
import net.minecraft.network.PacketByteBuf
import io.netty.buffer.Unpooled
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures network packets for replay.
 * Stores raw packet data with tick timestamps.
 */
object PacketCapture {
    
    // Queue of captured packets: Pair<tickTimestamp, packetData>
    private val packetQueue = ConcurrentLinkedQueue<CapturedPacket>()
    
    @Volatile
    var isCapturing: Boolean = false
        private set
    
    @Volatile
    private var currentTick: Int = 0
    
    data class CapturedPacket(
        val tick: Int,
        val packetId: Int,
        val data: ByteArray
    )
    
    fun onGameJoin() {
        println("[MCAP] Game joined - packet capture ready")
        currentTick = 0
    }
    
    fun startCapture() {
        isCapturing = true
        currentTick = 0
        packetQueue.clear()
        println("[MCAP] Packet capture started")
    }
    
    fun stopCapture() {
        isCapturing = false
        println("[MCAP] Packet capture stopped, ${packetQueue.size} packets captured")
    }
    
    fun onTick() {
        if (isCapturing) {
            currentTick++
        }
    }
    
    /**
     * Called from Mixin to capture a packet.
     * Returns the packet ID or -1 if not captured.
     */
    fun capturePacket(packetId: Int, data: ByteArray): Boolean {
        if (!isCapturing) return false
        
        packetQueue.add(CapturedPacket(currentTick, packetId, data))
        return true
    }
    
    /**
     * Drain captured packets to a byte array for storage.
     * Format per packet: u32 tick, u16 packetId, u16 dataLen, data[]
     */
    fun drainPackets(): ByteArray {
        val packets = mutableListOf<CapturedPacket>()
        while (true) {
            val p = packetQueue.poll() ?: break
            packets.add(p)
        }
        
        if (packets.isEmpty()) return ByteArray(0)
        
        // Calculate total size
        var totalSize = 0
        for (p in packets) {
            totalSize += 4 + 2 + 2 + p.data.size // tick + packetId + dataLen + data
        }
        
        val out = ByteArray(totalSize)
        var offset = 0
        
        for (p in packets) {
            // u32 tick (little-endian)
            out[offset++] = (p.tick and 0xFF).toByte()
            out[offset++] = ((p.tick shr 8) and 0xFF).toByte()
            out[offset++] = ((p.tick shr 16) and 0xFF).toByte()
            out[offset++] = ((p.tick shr 24) and 0xFF).toByte()
            
            // u16 packetId (little-endian)
            out[offset++] = (p.packetId and 0xFF).toByte()
            out[offset++] = ((p.packetId shr 8) and 0xFF).toByte()
            
            // u16 dataLen (little-endian)
            val len = p.data.size
            out[offset++] = (len and 0xFF).toByte()
            out[offset++] = ((len shr 8) and 0xFF).toByte()
            
            // data
            System.arraycopy(p.data, 0, out, offset, len)
            offset += len
        }
        
        return out
    }
    
    fun getQueueSize(): Int = packetQueue.size
}
