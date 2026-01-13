package dev.replaycraft.mcap.capture

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures network packets for replay.
 * Stores raw packet data with tick timestamps.
 */
object PacketCapture {
    
    // Queue of captured packets: Pair<tickTimestamp, packetData>
    private val packetQueue = ConcurrentLinkedQueue<CapturedPacket>()
    
    @Volatile
    private var isCapturingInternal: Boolean = false
    
    @JvmStatic
    fun isCapturing(): Boolean = isCapturingInternal
    
    @Volatile
    private var currentTick: Int = 0
    
    data class CapturedPacket(
        val tick: Int,
        val packetId: Int,
        val data: ByteArray
    )
    
    @JvmStatic
    fun onGameJoin() {
        currentTick = 0
    }
    
    @Volatile
    private var needsInitialCapture: Boolean = false
    
    fun startCapture() {
        isCapturingInternal = true
        currentTick = 0
        packetQueue.clear()
        needsInitialCapture = true
            }
    
    /**
     * Check if initial world capture is needed and perform it.
     * Must be called from the main thread (render thread).
     */
    fun checkInitialCapture() {
        if (needsInitialCapture && isCapturingInternal) {
            needsInitialCapture = false
            InitialWorldCapture.captureInitialBlocks()
        }
    }
    
    fun stopCapture() {
        isCapturingInternal = false
            }
    
    fun onTick() {
        if (isCapturingInternal) {
            currentTick++
        }
    }
    
    /**
     * Called from Mixin to capture a packet.
     * Returns the packet ID or -1 if not captured.
     */
    @JvmStatic
    fun capturePacket(packetId: Int, data: ByteArray): Boolean {
        if (!isCapturingInternal) return false
        
        packetQueue.add(CapturedPacket(currentTick, packetId, data))
        return true
    }
    
    /**
     * Capture an initial state packet at tick 0.
     * Used for capturing initial world state before any changes.
     */
    fun captureInitialPacket(packetId: Int, data: ByteArray) {
        // Always store at tick 0 so it replays first
        packetQueue.add(CapturedPacket(0, packetId, data))
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
