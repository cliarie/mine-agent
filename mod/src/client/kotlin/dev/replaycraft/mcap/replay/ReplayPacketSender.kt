package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket
import net.minecraft.network.packet.s2c.play.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Netty channel handler modeled after ReplayMod's FullReplaySender.
 *
 * Architecture (matching ReplayMod):
 * - Extends ChannelInboundHandlerAdapter, sits in the EmbeddedChannel pipeline
 *   AFTER the DecoderHandler (which decodes raw ByteBuf -> Packet objects)
 * - Receives decoded Packet<?> objects in channelRead()
 * - Filters out BAD_PACKETS (blacklist approach, like ReplayMod)
 * - Passes allowed packets downstream via super.channelRead() to packet_handler (ClientConnection)
 *
 * Packet dispatch flow:
 * 1. ReplayHandler calls channel.pipeline().fireChannelRead(rawByteBuf)
 * 2. DecoderHandler decodes ByteBuf -> Packet<?> object
 * 3. PacketBundler handles bundle splitting
 * 4. This handler receives the decoded Packet in channelRead()
 * 5. If not in BAD_PACKETS, passes to ClientConnection via super.channelRead()
 * 6. ClientConnection dispatches to ClientPlayNetworkHandler.onXxx() methods
 */
@Sharable
class ReplayPacketSender(
    private val replayHandle: Long
) : ChannelInboundHandlerAdapter() {

    /**
     * Packets that are completely ignored during replay.
     * Modeled after ReplayMod's BAD_PACKETS list - blacklist approach.
     * Most packets are allowed; only problematic ones are blocked.
     */
    companion object {
        private val BAD_PACKETS: Set<Class<out Packet<*>>> = setOf(
            // Login packets that shouldn't appear during PLAY state
            LoginHelloS2CPacket::class.java,
            // Disconnect would kick us out of the replay
            DisconnectS2CPacket::class.java,
            // Keep alive - no server to respond to
            KeepAliveS2CPacket::class.java,
            // Resource packs - don't want to download during replay
            ResourcePackSendS2CPacket::class.java,
            // Camera entity - would override our replay camera
            SetCameraEntityS2CPacket::class.java,
            // Title packets can be distracting during replay
            TitleS2CPacket::class.java,
            // These UI/stat packets are noise during replay
            HealthUpdateS2CPacket::class.java,
            OpenHorseScreenS2CPacket::class.java,
            CloseScreenS2CPacket::class.java,
            ScreenHandlerSlotUpdateS2CPacket::class.java,
            ScreenHandlerPropertyUpdateS2CPacket::class.java,
            SignEditorOpenS2CPacket::class.java,
            StatisticsS2CPacket::class.java,
            ExperienceBarUpdateS2CPacket::class.java,
            PlayerAbilitiesS2CPacket::class.java,
            // Recipes/advancements cause UI noise
            SynchronizeRecipesS2CPacket::class.java,
            AdvancementUpdateS2CPacket::class.java,
            SelectAdvancementTabS2CPacket::class.java,
            // Written book screen
            OpenWrittenBookS2CPacket::class.java,
            // Open screen (container GUIs) - skip to avoid phantom screens
            OpenScreenS2CPacket::class.java,
        )
    }

    /**
     * Process decoded packets flowing through the pipeline.
     * The DecoderHandler upstream has already converted raw ByteBuf -> Packet<?>.
     * We filter out BAD_PACKETS and pass the rest downstream to ClientConnection.
     */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Packet<*>) {
            if (BAD_PACKETS.contains(msg.javaClass)) {
                return // Drop this packet silently
            }
        }
        // Pass to next handler (ClientConnection / packet_handler)
        super.channelRead(ctx, msg)
    }

    /**
     * Read captured packets for a given tick from native storage and fire them
     * as raw ByteBuf into the pipeline. The DecoderHandler will decode them
     * into Packet objects which then flow through channelRead() above.
     *
     * Native format per packet: [u16 packetId LE][u32 dataLen LE][data bytes]
     * MC wire format: [varint packetId][data bytes]
     *
     * Returns the number of packets fired.
     */
    fun firePacketsForTick(pipeline: io.netty.channel.ChannelPipeline, tick: Int): Int {
        if (replayHandle < 0) return 0

        val packetsData = try {
            NativeBridge.nativeReadPacketsForTick(replayHandle, tick)
        } catch (e: Exception) {
            return 0
        }
        if (packetsData.isEmpty()) return 0

        val buf = ByteBuffer.wrap(packetsData).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0

        while (buf.remaining() >= 6) {
            val packetId = buf.short.toInt() and 0xFFFF
            val dataLen = buf.int

            if (dataLen < 0 || buf.remaining() < dataLen) break

            val packetData = ByteArray(dataLen)
            buf.get(packetData)

            val rawBuf = Unpooled.buffer()
            try {
                // Reconstruct raw MC wire format: varint(packetId) + data
                // This is what the DecoderHandler expects
                writeVarInt(rawBuf, packetId)
                rawBuf.writeBytes(packetData)

                // Fire into the pipeline head - flows through:
                // DecoderHandler -> PacketBundler -> ReplayPacketSender -> ClientConnection
                pipeline.fireChannelRead(rawBuf)
                count++
            } catch (e: Exception) {
                // Release ByteBuf on error to prevent leak
                rawBuf.release()
            }
        }

        return count
    }

    /**
     * Write a VarInt to a ByteBuf (MC wire format).
     */
    private fun writeVarInt(buf: ByteBuf, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                buf.writeByte(v)
                return
            }
            buf.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }
}
