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
     * This blocks until the flush is complete.
     */
    fun flush() {
        if (!running) return
        synchronized(flushLock) {
            flushComplete = false
            flushRequested = true
            // Wait for flush to complete (max 2 seconds)
            val startTime = System.currentTimeMillis()
            while (!flushComplete && System.currentTimeMillis() - startTime < 2000) {
                try {
                    flushLock.wait(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }
}
