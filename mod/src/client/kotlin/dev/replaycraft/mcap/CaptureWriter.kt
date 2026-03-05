package dev.replaycraft.mcap

import dev.replaycraft.mcap.capture.PacketCapture
import dev.replaycraft.mcap.capture.RawPacketCapture
import dev.replaycraft.mcap.capture.TickRingBuffer
import dev.replaycraft.mcap.native.NativeBridge

class CaptureWriter(
    private val buffer: TickRingBuffer,
    private val baseDir: String,
) {
    @Volatile private var running = true
    @Volatile private var sessionHandle: Long = -1
    private val flushLock = Object()
    @Volatile private var flushRequested = false
    @Volatile private var flushComplete = false

    private val thread = Thread({
        sessionHandle = NativeBridge.nativeInitSession(NativeBridge.defaultManifestJson(), baseDir)
        var currentStartTick = -1

        val tickBatch = ByteArray(buffer.recordSize * 200) // enough for 200 ticks
        
        // Start both capture systems:
        // - PacketCapture: selective mixin-based (legacy, kept for client-side events)
        // - RawPacketCapture: comprehensive Netty pipeline capture (all S2C packets)
        PacketCapture.startCapture()
        RawPacketCapture.startCapture()

        while (running) {
            val handle = sessionHandle
            
            // Drain tick data
            val drained = buffer.drainToByteArray(tickBatch)
            if (drained > 0) {
                if (currentStartTick < 0) currentStartTick = buffer.lastDrainedStartTick
                NativeBridge.nativeAppendTicks(handle, currentStartTick, tickBatch, drained)
                currentStartTick += (drained / buffer.recordSize)
            }
            
            // Drain raw packet data from Netty pipeline capture (comprehensive)
            val rawPacketData = RawPacketCapture.drainPackets()
            if (rawPacketData.isNotEmpty()) {
                NativeBridge.nativeAppendPackets(handle, rawPacketData, rawPacketData.size)
            }
            
            if (drained == 0 && rawPacketData.isEmpty()) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
            
            // Check if flush requested
            if (flushRequested) {
                synchronized(flushLock) {
                    // Close current session to flush all data
                    NativeBridge.nativeCloseSession(sessionHandle)
                    // Open a new session
                    sessionHandle = NativeBridge.nativeInitSession(NativeBridge.defaultManifestJson(), baseDir)
                    currentStartTick = -1
                    flushComplete = true
                    flushRequested = false
                    flushLock.notifyAll()
                }
            }
        }

        // Final drain: capture any remaining packets/ticks before closing session.
        // Without this, packets added after the last loop iteration are lost.
        val finalDrained = buffer.drainToByteArray(tickBatch)
        if (finalDrained > 0) {
            if (currentStartTick < 0) currentStartTick = buffer.lastDrainedStartTick
            NativeBridge.nativeAppendTicks(sessionHandle, currentStartTick, tickBatch, finalDrained)
        }
        val finalPackets = RawPacketCapture.drainPackets()
        if (finalPackets.isNotEmpty()) {
            NativeBridge.nativeAppendPackets(sessionHandle, finalPackets, finalPackets.size)
        }

        PacketCapture.stopCapture()
        RawPacketCapture.stopCapture()
        NativeBridge.nativeCloseSession(sessionHandle)
    }, "mcap-writer")

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
    }
    
    /**
     * Flush all pending data to disk by closing and reopening the session.
     * This is non-blocking to avoid freezing the main game thread (which would
     * cause the integrated server to disconnect the player in singleplayer).
     */
    fun flush() {
        if (!running) return
        flushRequested = true
        // Non-blocking: the writer thread will process the flush asynchronously.
        // Previous implementation blocked up to 2 seconds, which could cause
        // the integrated server to time out and disconnect the player.
    }
}
