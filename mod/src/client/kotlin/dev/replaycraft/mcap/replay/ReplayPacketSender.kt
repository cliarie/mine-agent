package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket
import net.minecraft.network.packet.s2c.play.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Netty channel handler modeled after ReplayMod's FullReplaySender.
 *
 * Architecture:
 * - Extends ChannelInboundHandlerAdapter, sits in the EmbeddedChannel pipeline
 * - Receives decoded Packet<?> objects in channelRead()
 * - Filters out BAD_PACKETS (blacklist approach, like ReplayMod)
 * - Passes allowed packets downstream via super.channelRead() to packet_handler (ClientConnection)
 *
 * Packet dispatch flow:
 * 1. firePacketsForTick() reads native capture data and decodes packets directly
 *    using NetworkState.PLAY.getPacketHandler() - bypasses DecoderHandler entirely
 *    to avoid ByteToMessageDecoder cumulation corruption on decode errors
 * 2. PacketBundler handles bundle splitting
 * 3. This handler receives the decoded Packet in channelRead()
 * 4. If not in BAD_PACKETS, passes to ClientConnection via super.channelRead()
 * 5. ClientConnection dispatches to ClientPlayNetworkHandler.onXxx() methods
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
        // MC 1.20.1 PLAY CLIENTBOUND has ~111 packet types (indices 0-110).
        // Any packet ID >= this threshold is invalid (e.g., 65535 from getPacketId
        // returning -1 for unregistered types, stored as u16 0xFFFF).
        private const val MAX_PLAY_PACKET_ID = 256

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
     * Read captured packets for a given tick from native storage, decode them
     * directly using NetworkState.PLAY.getPacketHandler(), and fire the decoded
     * Packet<?> objects into the pipeline AFTER the decoder position.
     *
     * We bypass the DecoderHandler (ByteToMessageDecoder) entirely because:
     * - ByteToMessageDecoder has internal cumulation state that gets corrupted
     *   if any packet fails to decode (e.g., invalid packet ID)
     * - Once corrupted, ALL subsequent packets fail with cascading errors
     * - By decoding ourselves, each packet is independent and errors are isolated
     *
     * Native format per packet: [u16 packetId LE][u32 dataLen LE][data bytes]
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

        val nativeBuf = ByteBuffer.wrap(packetsData).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0

        // Get the context for firing decoded packets - fire from the bundler context
        // so packets go through: PacketBundler -> ReplayPacketSender -> ClientConnection
        // This bypasses the DecoderHandler entirely.
        val bundlerCtx = pipeline.context("bundler")
        if (bundlerCtx == null) {
            // Fallback: fire from pipeline head (goes through decoder)
            return firePacketsForTickLegacy(pipeline, nativeBuf)
        }

        while (nativeBuf.remaining() >= 6) {
            val packetId = nativeBuf.short.toInt() and 0xFFFF
            val dataLen = nativeBuf.int

            if (dataLen < 0 || nativeBuf.remaining() < dataLen) break

            val packetData = ByteArray(dataLen)
            nativeBuf.get(packetData)

            // Skip invalid packet IDs (e.g., 65535 from getPacketId returning -1
            // for unregistered packet types like BundleSplitterPacket)
            if (packetId >= MAX_PLAY_PACKET_ID) {
                continue
            }

            try {
                // Decode the packet directly using NetworkState.PLAY
                // This is the same thing DecoderHandler.decode() does internally,
                // but without the ByteToMessageDecoder cumulation wrapper
                val packetByteBuf = PacketByteBuf(Unpooled.wrappedBuffer(packetData))
                val packet = NetworkState.PLAY.getPacketHandler(
                    NetworkSide.CLIENTBOUND, packetId, packetByteBuf
                )
                packetByteBuf.release()

                if (packet != null) {
                    // Fire the decoded packet into the pipeline starting at the bundler
                    // This goes through: PacketBundler -> ReplayPacketSender -> ClientConnection
                    bundlerCtx.fireChannelRead(packet)
                    count++
                }
            } catch (e: Exception) {
                // Skip packets that fail to decode - errors are isolated per-packet
                // (no cumulation corruption since we don't use ByteToMessageDecoder)
            }
        }

        return count
    }

    /**
     * Fallback: fire raw ByteBuf through the full pipeline (including decoder).
     * Used only if the bundler context is not found.
     */
    private fun firePacketsForTickLegacy(
        pipeline: io.netty.channel.ChannelPipeline,
        nativeBuf: ByteBuffer
    ): Int {
        var count = 0
        while (nativeBuf.remaining() >= 6) {
            val packetId = nativeBuf.short.toInt() and 0xFFFF
            val dataLen = nativeBuf.int

            if (dataLen < 0 || nativeBuf.remaining() < dataLen) break

            val packetData = ByteArray(dataLen)
            nativeBuf.get(packetData)

            if (packetId >= MAX_PLAY_PACKET_ID) continue

            val rawBuf = Unpooled.buffer()
            try {
                writeVarInt(rawBuf, packetId)
                rawBuf.writeBytes(packetData)
                pipeline.fireChannelRead(rawBuf)
                count++
            } catch (e: Exception) {
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
