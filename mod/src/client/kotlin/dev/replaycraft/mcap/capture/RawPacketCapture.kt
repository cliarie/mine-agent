package dev.replaycraft.mcap.capture

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.ClientConnection
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures ALL incoming S2C packets at the Netty pipeline level.
 *
 * This replaces the selective mixin-based capture with comprehensive capture,
 * matching how ReplayMod records packets. Every single packet from the server
 * is stored as raw bytes with a millisecond timestamp and tick number.
 *
 * Storage format per packet in the queue:
 *   u32 tick, u32 timestamp_ms, u32 packet_id, byte[] raw_data
 *
 * On disk (packets.bin v2):
 *   u32 tick, u32 timestamp_ms, u16 packet_id, u32 data_len, data...
 */
object RawPacketCapture {

    const val HANDLER_NAME = "mcap_recorder"

    data class CapturedRawPacket(
        val tick: Int,
        val timestampMs: Int,
        val packetId: Int,
        val data: ByteArray
    )

    internal val queue = ConcurrentLinkedQueue<CapturedRawPacket>()

    @Volatile
    private var capturing = false

    @Volatile
    var currentTick: Int = 0
        private set

    @Volatile
    internal var startTimeMs: Long = 0L

    @JvmStatic
    fun isCapturing(): Boolean = capturing

    fun startCapture() {
        capturing = true
        currentTick = 0
        startTimeMs = System.currentTimeMillis()
        queue.clear()
    }

    fun stopCapture() {
        capturing = false
    }

    fun onTick() {
        if (capturing) currentTick++
    }

    fun onGameJoin() {
        currentTick = 0
        startTimeMs = System.currentTimeMillis()
    }

    /**
     * Called from the Netty pipeline handler to record a raw packet.
     * The packet has already been decoded by the MC decoder, so we
     * re-serialize it to bytes for storage.
     */
    fun captureDecodedPacket(packet: Packet<*>) {
        if (!capturing) return
        try {
            val state = NetworkState.PLAY
            val packetId = state.getPacketId(NetworkSide.CLIENTBOUND, packet)
            // getPacketId returns -1 (not null) for unregistered packet types
            // (e.g. BundleSplitterPacket) via Object2IntMap default value.
            // -1 stored as u16 becomes 65535, causing decoder errors during replay.
            if (packetId < 0) return

            val buf = PacketByteBuf(Unpooled.buffer())
            packet.write(buf)
            val data = ByteArray(buf.readableBytes())
            buf.readBytes(data)
            buf.release()

            val timestampMs = (System.currentTimeMillis() - startTimeMs).toInt()
            queue.add(CapturedRawPacket(currentTick, timestampMs, packetId, data))
        } catch (_: Exception) {
            // Ignore serialization errors for packets we can't encode
        }
    }

    /**
     * Called from the Netty pipeline handler to record raw ByteBuf bytes
     * captured before the decoder. This is the most faithful capture.
     * The ByteBuf contains: [varint packet_id][packet_data]
     */
    fun captureRawBytes(buf: ByteBuf) {
        if (!capturing) return
        try {
            if (buf.readableBytes() <= 0) return
            val data = ByteArray(buf.readableBytes())
            buf.getBytes(buf.readerIndex(), data)
            val timestampMs = (System.currentTimeMillis() - startTimeMs).toInt()

            // Parse varint packet ID from the raw bytes
            var packetId = 0
            var shift = 0
            var idx = 0
            while (idx < data.size && idx < 5) {
                val b = data[idx].toInt() and 0xFF
                packetId = packetId or ((b and 0x7F) shl shift)
                shift += 7
                idx++
                if (b and 0x80 == 0) break
            }

            // Store just the data portion (after the varint packet ID)
            val packetData = data.copyOfRange(idx, data.size)
            queue.add(CapturedRawPacket(currentTick, timestampMs, packetId, packetData))
        } catch (_: Exception) {
            // Ignore errors
        }
    }

    /**
     * Drain all captured packets into a byte array for storage.
     * Format per packet: u32 tick, u32 timestamp_ms, u16 packetId, u32 dataLen, data[]
     */
    fun drainPackets(): ByteArray {
        val packets = mutableListOf<CapturedRawPacket>()
        while (true) {
            val p = queue.poll() ?: break
            packets.add(p)
        }
        if (packets.isEmpty()) return ByteArray(0)

        var totalSize = 0
        for (p in packets) {
            totalSize += 4 + 4 + 2 + 4 + p.data.size
        }

        val out = ByteArray(totalSize)
        var offset = 0

        for (p in packets) {
            // u32 tick (little-endian)
            out[offset++] = (p.tick and 0xFF).toByte()
            out[offset++] = ((p.tick shr 8) and 0xFF).toByte()
            out[offset++] = ((p.tick shr 16) and 0xFF).toByte()
            out[offset++] = ((p.tick shr 24) and 0xFF).toByte()

            // u32 timestamp_ms (little-endian)
            out[offset++] = (p.timestampMs and 0xFF).toByte()
            out[offset++] = ((p.timestampMs shr 8) and 0xFF).toByte()
            out[offset++] = ((p.timestampMs shr 16) and 0xFF).toByte()
            out[offset++] = ((p.timestampMs shr 24) and 0xFF).toByte()

            // u16 packetId (little-endian)
            out[offset++] = (p.packetId and 0xFF).toByte()
            out[offset++] = ((p.packetId shr 8) and 0xFF).toByte()

            // u32 dataLen (little-endian)
            val len = p.data.size
            out[offset++] = (len and 0xFF).toByte()
            out[offset++] = ((len shr 8) and 0xFF).toByte()
            out[offset++] = ((len shr 16) and 0xFF).toByte()
            out[offset++] = ((len shr 24) and 0xFF).toByte()

            // data
            System.arraycopy(p.data, 0, out, offset, len)
            offset += len
        }

        return out
    }

    fun getQueueSize(): Int = queue.size

    /**
     * Install the packet recorder into the Netty pipeline of a ClientConnection.
     * Should be called when a play connection is established (e.g., onGameJoin).
     */
    fun installOnChannel(channel: Channel) {
        if (channel.pipeline().get(HANDLER_NAME) != null) return

        // Try to add before the decoder to capture raw bytes
        if (channel.pipeline().get("decoder") != null) {
            channel.pipeline().addAfter("decoder", HANDLER_NAME, PipelinePacketHandler())
        } else {
            // Integrated server: no decoder in pipeline, packets pass directly
            channel.pipeline().addFirst(HANDLER_NAME, PipelinePacketHandler())
        }
    }

    /**
     * Remove the packet recorder from the Netty pipeline.
     */
    fun removeFromChannel(channel: Channel) {
        if (channel.pipeline().get(HANDLER_NAME) != null) {
            channel.pipeline().remove(HANDLER_NAME)
        }
    }

    /**
     * Netty handler that captures all incoming packets.
     * Placed after the decoder, so it receives decoded Packet<?> objects.
     */
    private class PipelinePacketHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is Packet<*>) {
                captureDecodedPacket(msg)
            }
            // Always pass through to the next handler
            ctx.fireChannelRead(msg)
        }
    }
}
