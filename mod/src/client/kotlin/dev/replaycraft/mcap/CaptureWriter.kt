package dev.replaycraft.mcap

import dev.replaycraft.mcap.capture.PacketCapture
import dev.replaycraft.mcap.capture.TickRingBuffer
import dev.replaycraft.mcap.native.NativeBridge

class CaptureWriter(
    private val buffer: TickRingBuffer,
    private val baseDir: String,
) {
    @Volatile private var running = true

    private val thread = Thread({
        val handle = NativeBridge.nativeInitSession(NativeBridge.defaultManifestJson(), baseDir)
        var currentStartTick = -1

        val tickBatch = ByteArray(4096)
        
        // Start packet capture
        PacketCapture.startCapture()

        while (running) {
            // Drain tick data
            val drained = buffer.drainToByteArray(tickBatch)
            if (drained > 0) {
                if (currentStartTick < 0) currentStartTick = buffer.lastDrainedStartTick
                NativeBridge.nativeAppendTicks(handle, currentStartTick, tickBatch, drained)
                currentStartTick += (drained / buffer.recordSize)
            }
            
            // Drain packet data
            val packetData = PacketCapture.drainPackets()
            if (packetData.isNotEmpty()) {
                NativeBridge.nativeAppendPackets(handle, packetData, packetData.size)
            }
            
            if (drained == 0 && packetData.isEmpty()) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }

        PacketCapture.stopCapture()
        NativeBridge.nativeCloseSession(handle)
    }, "mcap-writer")

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
    }
}
