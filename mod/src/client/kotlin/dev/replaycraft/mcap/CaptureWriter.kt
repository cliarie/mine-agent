package dev.replaycraft.mcap

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

        val batch = ByteArray(4096)

        while (running) {
            val drained = buffer.drainToByteArray(batch)
            if (drained == 0) {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
                continue
            }

            if (currentStartTick < 0) currentStartTick = buffer.lastDrainedStartTick
            NativeBridge.nativeAppendTicks(handle, currentStartTick, batch, drained)
            currentStartTick += (drained / buffer.recordSize)
        }

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
