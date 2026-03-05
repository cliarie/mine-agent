package dev.replaycraft.mcap.capture

import io.netty.buffer.Unpooled
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.EquipmentSlot
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.play.*

/**
 * Injects synthetic S2C packets for the local player's state each tick.
 *
 * The server never sends position/equipment/animation packets for the local
 * player (the client already knows its own state). But during replay we need
 * this data to reconstruct first-person POV. Following ReplayMod's
 * RecordingEventHandler, we inject synthetic packets:
 *
 * - EntityPositionS2CPacket  (player world position)
 * - EntityVelocityUpdateS2CPacket (player velocity)
 * - EntitySetHeadYawS2CPacket (head rotation)
 * - EntityEquipmentUpdateS2CPacket (held items, armor)
 * - EntityAnimationS2CPacket (arm swing)
 *
 * These are serialized and fed into RawPacketCapture as if the server sent them.
 */
object RecordingEventHandler {

    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var lastZ = Double.NaN
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var wasSwinging = false

    fun reset() {
        lastX = Double.NaN
        lastY = Double.NaN
        lastZ = Double.NaN
        lastYaw = Float.NaN
        lastPitch = Float.NaN
        wasSwinging = false
    }

    /**
     * Called each world tick to inject synthetic packets for the local player.
     */
    fun onPlayerTick() {
        if (!RawPacketCapture.isCapturing()) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        // --- Position packet (only if moved) ---
        val x = player.x
        val y = player.y
        val z = player.z
        val yaw = player.yaw
        val pitch = player.pitch

        val moved = lastX.isNaN() ||
            (x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ) > 0.0001 ||
            yaw != lastYaw || pitch != lastPitch

        if (moved) {
            try {
                val posBuf = PacketByteBuf(Unpooled.buffer())
                posBuf.writeVarInt(player.id)
                posBuf.writeDouble(x)
                posBuf.writeDouble(y)
                posBuf.writeDouble(z)
                posBuf.writeByte((yaw * 256.0f / 360.0f).toInt())
                posBuf.writeByte((pitch * 256.0f / 360.0f).toInt())
                posBuf.writeBoolean(player.isOnGround)
                // Construct the actual packet to get its ID
                val posPacket = EntityPositionS2CPacket(posBuf)
                injectPacket(posPacket, posBuf)
                posBuf.release()
            } catch (_: Exception) {}

            lastX = x
            lastY = y
            lastZ = z
            lastYaw = yaw
            lastPitch = pitch
        }

        // --- Velocity packet ---
        val vel = player.velocity
        if (vel.lengthSquared() > 0.0001) {
            try {
                val velBuf = PacketByteBuf(Unpooled.buffer())
                velBuf.writeVarInt(player.id)
                velBuf.writeShort((vel.x.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                velBuf.writeShort((vel.y.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                velBuf.writeShort((vel.z.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                val velPacket = EntityVelocityUpdateS2CPacket(velBuf)
                injectPacket(velPacket, velBuf)
                velBuf.release()
            } catch (_: Exception) {}
        }

        // --- Head yaw ---
        try {
            val headBuf = PacketByteBuf(Unpooled.buffer())
            headBuf.writeVarInt(player.id)
            headBuf.writeByte((player.headYaw * 256.0f / 360.0f).toInt())
            val headPacket = EntitySetHeadYawS2CPacket(headBuf)
            injectPacket(headPacket, headBuf)
            headBuf.release()
        } catch (_: Exception) {}

        // --- Equipment (every 20 ticks = 1 second) ---
        if (RawPacketCapture.currentTick % 20 == 0) {
            try {
                val equipBuf = PacketByteBuf(Unpooled.buffer())
                equipBuf.writeVarInt(player.id)
                val slots = listOf(
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET
                )
                for ((idx, slot) in slots.withIndex()) {
                    val stack = player.getEquippedStack(slot)
                    val slotId = if (idx < slots.size - 1) {
                        slot.ordinal or 0x80
                    } else {
                        slot.ordinal
                    }
                    equipBuf.writeByte(slotId)
                    equipBuf.writeItemStack(stack)
                }
                val equipPacket = EntityEquipmentUpdateS2CPacket(equipBuf)
                injectPacket(equipPacket, equipBuf)
                equipBuf.release()
            } catch (_: Exception) {}
        }

        // --- Arm swing animation ---
        val swinging = player.handSwinging
        if (swinging && !wasSwinging) {
            try {
                val animBuf = PacketByteBuf(Unpooled.buffer())
                animBuf.writeVarInt(player.id)
                animBuf.writeByte(0) // 0 = swing main hand
                val animPacket = EntityAnimationS2CPacket(animBuf)
                injectPacket(animPacket, animBuf)
                animBuf.release()
            } catch (_: Exception) {}
        }
        wasSwinging = swinging
    }

    /**
     * Inject a synthetic packet into the RawPacketCapture queue.
     * We use the packet instance to look up its ID, then store the
     * raw data bytes from the buffer.
     */
    private fun injectPacket(packet: Packet<*>, buf: PacketByteBuf) {
        try {
            val state = NetworkState.PLAY
            val packetId = state.getPacketId(NetworkSide.CLIENTBOUND, packet)
            if (packetId < 0) return

            // Re-serialize the packet to get clean data bytes
            val writeBuf = PacketByteBuf(Unpooled.buffer())
            packet.write(writeBuf)
            val data = ByteArray(writeBuf.readableBytes())
            writeBuf.readBytes(data)
            writeBuf.release()

            val timestampMs = (System.currentTimeMillis() - RawPacketCapture.startTimeMs).toInt()
            RawPacketCapture.queue.add(
                RawPacketCapture.CapturedRawPacket(
                    RawPacketCapture.currentTick,
                    timestampMs,
                    packetId,
                    data
                )
            )
        } catch (_: Exception) {
            // Ignore injection errors
        }
    }
}
