package dev.replaycraft.mcap.capture

import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.GameOptions
import net.minecraft.entity.player.PlayerEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class TickRingBuffer(val capacity: Int) {
    // Fixed-size record for JNI batching.
    // Layout (little-endian):
    // u16 flags, u8 hotbarOr255, i16 yaw_fp, i16 pitch_fp, u32 tick
    // total = 2 + 1 + 2 + 2 + 4 = 11, pad to 12 for alignment
    val recordSize: Int = 12

    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity * recordSize).order(ByteOrder.LITTLE_ENDIAN)

    private val writeIdx = AtomicInteger(0)
    private val readIdx = AtomicInteger(0)

    @Volatile var lastDrainedStartTick: Int = 0
        private set

    private var localTick: Int = 0

    fun tryWriteFromClient(client: MinecraftClient, player: PlayerEntity) {
        val w = writeIdx.get()
        val r = readIdx.get()
        if (w - r >= capacity) {
            // Buffer full: drop optional data? For MVP we just stop writing new records.
            return
        }

        val options: GameOptions = client.options

        var flags = 0
        if (options.forwardKey.isPressed) flags = flags or (1 shl 0)
        if (options.backKey.isPressed) flags = flags or (1 shl 1)
        if (options.leftKey.isPressed) flags = flags or (1 shl 2)
        if (options.rightKey.isPressed) flags = flags or (1 shl 3)
        if (options.jumpKey.isPressed) flags = flags or (1 shl 4)
        if (options.sneakKey.isPressed) flags = flags or (1 shl 5)
        if (options.sprintKey.isPressed) flags = flags or (1 shl 6)

        val hotbar = player.inventory.selectedSlot

        val yawFp = (player.yaw * 100.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        val pitchFp = (player.pitch * 100.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        val slot = (w % capacity) * recordSize
        buf.putShort(slot + 0, flags.toShort())
        buf.put(slot + 2, if (hotbar in 0..8) hotbar.toByte() else 0xFF.toByte())
        buf.putShort(slot + 3, yawFp)
        buf.putShort(slot + 5, pitchFp)
        buf.putInt(slot + 7, localTick)
        buf.put(slot + 11, 0) // padding

        localTick++
        writeIdx.lazySet(w + 1)
    }

    fun drainToByteArray(out: ByteArray): Int {
        val r = readIdx.get()
        val w = writeIdx.get()
        val available = w - r
        if (available <= 0) return 0

        val maxRecords = out.size / recordSize
        val toRead = minOf(available, maxRecords)
        if (toRead <= 0) return 0

        lastDrainedStartTick = buf.getInt(((r % capacity) * recordSize) + 7)

        var outOff = 0
        for (i in 0 until toRead) {
            val idx = (r + i) % capacity
            val base = idx * recordSize
            buf.position(base)
            buf.get(out, outOff, recordSize)
            outOff += recordSize
        }

        readIdx.lazySet(r + toRead)
        return outOff
    }
}
