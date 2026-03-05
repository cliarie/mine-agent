package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.play.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Netty channel handler that sits in the fake EmbeddedChannel pipeline
 * and feeds captured S2C packets into the channel for processing by
 * the ClientPlayNetworkHandler.
 *
 * Similar to ReplayMod's FullReplaySender: reads packets from our
 * capture format and dispatches them through the Netty pipeline so
 * they are handled exactly as if they came from a real server.
 *
 * Key difference from the old approach (calling packet.apply() directly):
 * packets go through the full Netty pipeline including bundle splitting,
 * decompression, etc., which avoids crashes from pipeline-only packets
 * like BundleSplitterPacket.
 */
@Sharable
class ReplayPacketSender(
    private val replayHandle: Long
) : ChannelDuplexHandler() {

    private var ctx: ChannelHandlerContext? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        this.ctx = ctx
        ctx.fireChannelActive()
    }

    /**
     * Intercept outgoing packets from the client to prevent them from
     * going anywhere (there's no real server on the other end).
     */
    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
        // Swallow all outgoing packets - we're replaying, not sending
        promise.setSuccess()
    }

    /**
     * Send all captured packets for a given tick into the channel.
     * Each packet is constructed from its raw bytes and fired into
     * the pipeline as a decoded Packet<?> object.
     *
     * Returns the number of packets dispatched.
     */
    fun sendPacketsForTick(tick: Int): Int {
        val context = ctx ?: return 0
        if (replayHandle < 0) return 0

        val packetsData = NativeBridge.nativeReadPacketsForTick(replayHandle, tick)
        if (packetsData.isEmpty()) return 0

        val buf = ByteBuffer.wrap(packetsData).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0

        while (buf.remaining() >= 6) {
            val packetId = buf.short.toInt() and 0xFFFF
            val dataLen = buf.int

            if (buf.remaining() < dataLen) break

            val packetData = ByteArray(dataLen)
            buf.get(packetData)

            try {
                val pktBuf = PacketByteBuf(Unpooled.wrappedBuffer(packetData))
                try {
                    val state = NetworkState.PLAY
                    val packet = state.getPacketHandler(NetworkSide.CLIENTBOUND, packetId, pktBuf)

                    if (packet != null && !shouldSkipPacket(packet)) {
                        // Fire into the pipeline - this goes through the normal
                        // Netty handler chain ending at packet_handler (our fake ClientConnection)
                        context.fireChannelRead(packet)
                        count++
                    }
                } finally {
                    pktBuf.release()
                }
            } catch (e: Exception) {
                // Skip packets that fail to deserialize
            }
        }

        return count
    }

    /**
     * Packets to skip during replay. With a fake connection we can be
     * more permissive than before, but some packets still cause issues.
     *
     * Using a whitelist: only explicitly safe packet types are dispatched.
     */
    private fun shouldSkipPacket(packet: Packet<*>): Boolean {
        val isAllowed = when (packet) {
            // World join/respawn - essential for world setup
            is GameJoinS2CPacket -> true
            is PlayerRespawnS2CPacket -> true
            // Player position/abilities
            is PlayerPositionLookS2CPacket -> true
            is PlayerAbilitiesS2CPacket -> true
            // Chunk data - essential for rendering the world
            is ChunkDataS2CPacket -> true
            is UnloadChunkS2CPacket -> true
            is LightUpdateS2CPacket -> true
            // World state
            is BlockUpdateS2CPacket -> true
            is ChunkDeltaUpdateS2CPacket -> true
            is BlockBreakingProgressS2CPacket -> true
            is BlockEventS2CPacket -> true
            is BlockEntityUpdateS2CPacket -> true
            // Entity spawning
            is EntitySpawnS2CPacket -> true
            is PlayerSpawnS2CPacket -> true
            is ExperienceOrbSpawnS2CPacket -> true
            // Entity state
            is EntityAnimationS2CPacket -> true
            is EntityStatusS2CPacket -> true
            is EntitiesDestroyS2CPacket -> true
            is EntityEquipmentUpdateS2CPacket -> true
            is EntityTrackerUpdateS2CPacket -> true
            is EntityAttachS2CPacket -> true
            is EntityAttributesS2CPacket -> true
            // Entity movement
            is EntityPositionS2CPacket -> true
            is EntityVelocityUpdateS2CPacket -> true
            is EntitySetHeadYawS2CPacket -> true
            is EntityS2CPacket -> true
            // Inventory and UI - the key packets for full replay!
            is InventoryS2CPacket -> true
            is ScreenHandlerSlotUpdateS2CPacket -> true
            is ScreenHandlerPropertyUpdateS2CPacket -> true
            is OpenScreenS2CPacket -> true
            is CloseScreenS2CPacket -> true
            is OpenHorseScreenS2CPacket -> true
            // Player state
            is HealthUpdateS2CPacket -> true
            is ExperienceBarUpdateS2CPacket -> true
            is UpdateSelectedSlotS2CPacket -> true
            // Effects
            is PlaySoundS2CPacket -> true
            is PlaySoundFromEntityS2CPacket -> true
            is ParticleS2CPacket -> true
            is WorldEventS2CPacket -> true
            is EntityDamageS2CPacket -> true
            is EntityStatusEffectS2CPacket -> true
            is RemoveEntityStatusEffectS2CPacket -> true
            // Time and weather
            is WorldTimeUpdateS2CPacket -> true
            is GameStateChangeS2CPacket -> true
            // Chat / overlay
            is OverlayMessageS2CPacket -> true
            is TitleS2CPacket -> true
            is SubtitleS2CPacket -> true
            // Scoreboard
            is ScoreboardDisplayS2CPacket -> true
            is ScoreboardObjectiveUpdateS2CPacket -> true
            is ScoreboardPlayerUpdateS2CPacket -> true
            is TeamS2CPacket -> true
            // Custom payload (mod channels, etc.)
            is CustomPayloadS2CPacket -> true
            // Features/tags needed for world setup
            is FeaturesS2CPacket -> true
            is SynchronizeTagsS2CPacket -> true
            is SynchronizeRecipesS2CPacket -> true
            // Player list (tab)
            is PlayerListS2CPacket -> true
            is PlayerListHeaderS2CPacket -> true
            is PlayerRemoveS2CPacket -> true
            // Difficulty/border
            is DifficultyS2CPacket -> true
            is WorldBorderInitializeS2CPacket -> true
            is WorldBorderSizeChangedS2CPacket -> true
            is WorldBorderCenterChangedS2CPacket -> true
            is WorldBorderInterpolateSizeS2CPacket -> true
            is WorldBorderWarningTimeChangedS2CPacket -> true
            is WorldBorderWarningBlocksChangedS2CPacket -> true
            // Commands (needed for chat autocomplete)
            is CommandTreeS2CPacket -> true
            // Entity passenger/leash
            is EntityPassengersSetS2CPacket -> true
            // Keep alive - needed to prevent timeout on fake connection
            // Actually we handle this specially - skip it
            is KeepAliveS2CPacket -> false
            // Disconnect - definitely skip
            is DisconnectS2CPacket -> false
            // Default: skip unknown packets (safer than allowing them)
            else -> false
        }
        return !isAllowed
    }
}
