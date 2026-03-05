package dev.replaycraft.mcap.capture

import io.netty.buffer.Unpooled
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import net.minecraft.entity.Entity
import net.minecraft.network.packet.s2c.play.*
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos

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
 * - EntityAnimationS2CPacket (arm swing, sleep wake)
 * - EntityPassengersSetS2CPacket (vehicle mount/dismount)
 * - WorldEventS2CPacket (client-side effects like block break sounds)
 * - BlockBreakingProgressS2CPacket (block breaking animation, via WorldRendererMixin)
 *
 * These are serialized and fed into RawPacketCapture as if the server sent them.
 */
object RecordingEventHandler {

    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var lastZ = Double.NaN
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var lastRotationYawHead: Int? = null
    private var lastRiding = -1
    private var lastVehicle: Entity? = null
    private var wasSleeping = false
    private var ticksSinceLastCorrection = 0
    private val playerItems = Array<ItemStack>(EquipmentSlot.values().size) { ItemStack.EMPTY }

    fun reset() {
        lastX = Double.NaN
        lastY = Double.NaN
        lastZ = Double.NaN
        lastYaw = Float.NaN
        lastPitch = Float.NaN
        lastRotationYawHead = null
        lastRiding = -1
        lastVehicle = null
        wasSleeping = false
        ticksSinceLastCorrection = 0
        playerItems.fill(ItemStack.EMPTY)
    }

    /**
     * Called each world tick to inject synthetic packets for the local player.
     */
    fun onPlayerTick() {
        if (!RawPacketCapture.isCapturing()) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        // --- Position packet (matching ReplayMod's logic) ---
        val x = player.x
        val y = player.y
        val z = player.z

        var force = false
        if (lastX.isNaN() || lastY.isNaN() || lastZ.isNaN()) {
            force = true
            lastX = x
            lastY = y
            lastZ = z
        }

        ticksSinceLastCorrection++
        if (ticksSinceLastCorrection >= 100) {
            ticksSinceLastCorrection = 0
            force = true
        }

        val dx = x - lastX
        val dy = y - lastY
        val dz = z - lastZ

        lastX = x
        lastY = y
        lastZ = z

        // ReplayMod uses 8.0 as max relative distance threshold
        val maxRelDist = 8.0

        try {
            if (force || Math.abs(dx) > maxRelDist || Math.abs(dy) > maxRelDist || Math.abs(dz) > maxRelDist) {
                // Absolute teleport packet
                val posBuf = PacketByteBuf(Unpooled.buffer())
                posBuf.writeVarInt(player.id)
                posBuf.writeDouble(x)
                posBuf.writeDouble(y)
                posBuf.writeDouble(z)
                posBuf.writeByte((player.yaw * 256.0f / 360.0f).toInt())
                posBuf.writeByte((player.pitch * 256.0f / 360.0f).toInt())
                posBuf.writeBoolean(player.isOnGround)
                val posPacket = EntityPositionS2CPacket(posBuf)
                injectPacket(posPacket, posBuf)
                posBuf.release()
            } else {
                // Relative movement + rotation packet (matches ReplayMod)
                val newYaw = (player.yaw * 256.0f / 360.0f).toInt().toByte()
                val newPitch = (player.pitch * 256.0f / 360.0f).toInt().toByte()
                val packet = EntityS2CPacket.RotateAndMoveRelative(
                    player.id,
                    Math.round(dx * 4096).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort(),
                    Math.round(dy * 4096).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort(),
                    Math.round(dz * 4096).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort(),
                    newYaw,
                    newPitch,
                    player.isOnGround
                )
                injectPacket(packet)
            }
        } catch (_: Exception) {}

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

        // --- Head yaw (only if changed, like ReplayMod) ---
        val rotationYawHead = (player.headYaw * 256.0f / 360.0f).toInt()
        if (rotationYawHead != lastRotationYawHead) {
            try {
                val headBuf = PacketByteBuf(Unpooled.buffer())
                headBuf.writeVarInt(player.id)
                headBuf.writeByte(rotationYawHead)
                val headPacket = EntitySetHeadYawS2CPacket(headBuf)
                injectPacket(headPacket, headBuf)
                headBuf.release()
            } catch (_: Exception) {}
            lastRotationYawHead = rotationYawHead
        }

        // --- Equipment (on change, matching ReplayMod) ---
        for (slot in EquipmentSlot.values()) {
            val stack = player.getEquippedStack(slot)
            val index = slot.ordinal
            if (!ItemStack.areEqual(playerItems[index], stack)) {
                val stackCopy = if (stack.isEmpty) ItemStack.EMPTY else stack.copy()
                playerItems[index] = stackCopy
                try {
                    injectPacket(EntityEquipmentUpdateS2CPacket(
                        player.id,
                        listOf(com.mojang.datafixers.util.Pair(slot, stackCopy))
                    ))
                } catch (_: Exception) {}
            }
        }

        // --- Arm swing animation (matching ReplayMod: check handSwingTicks == 0) ---
        if (player.handSwinging && player.handSwingTicks == 0) {
            try {
                val animBuf = PacketByteBuf(Unpooled.buffer())
                animBuf.writeVarInt(player.id)
                val animType = if (player.preferredHand == Hand.MAIN_HAND) 0 else 3
                animBuf.writeByte(animType)
                val animPacket = EntityAnimationS2CPacket(animBuf)
                injectPacket(animPacket, animBuf)
                animBuf.release()
            } catch (_: Exception) {}
        }

        // --- Vehicle mount/dismount (using EntityPassengersSetS2CPacket) ---
        // In MC 1.20.1, EntityAttachS2CPacket is for leashes, not vehicles.
        // Vehicle mounting uses EntityPassengersSetS2CPacket which lists passenger IDs.
        val vehicle = player.vehicle
        val vehicleId = vehicle?.id ?: -1
        if (lastRiding != vehicleId) {
            try {
                if (vehicle != null) {
                    // Player mounted a vehicle: send passengers for the new vehicle
                    injectPacket(EntityPassengersSetS2CPacket(vehicle))
                } else if (lastVehicle != null) {
                    // Player dismounted: send updated passengers for old vehicle (player removed)
                    injectPacket(EntityPassengersSetS2CPacket(lastVehicle!!))
                }
            } catch (_: Exception) {}
            lastRiding = vehicleId
            lastVehicle = vehicle
        }

        // --- Sleep wake animation (matching ReplayMod) ---
        if (!player.isSleeping && wasSleeping) {
            try {
                val animBuf = PacketByteBuf(Unpooled.buffer())
                animBuf.writeVarInt(player.id)
                animBuf.writeByte(2) // 2 = leave bed animation
                val animPacket = EntityAnimationS2CPacket(animBuf)
                injectPacket(animPacket, animBuf)
                animBuf.release()
            } catch (_: Exception) {}
            wasSleeping = false
        }
        if (player.isSleeping) {
            wasSleeping = true
        }
    }

    /**
     * Called from WorldRendererMixin when a block breaking progress event occurs.
     * Mirrors ReplayMod's RecordingEventHandler.onBlockBreakAnim().
     *
     * The server only sends BlockBreakingProgressS2CPacket to OTHER clients,
     * not to the player doing the breaking. We inject a synthetic packet so
     * that the block breaking animation is visible during replay.
     */
    fun onBlockBreakAnim(breakerId: Int, pos: BlockPos, progress: Int) {
        if (!RawPacketCapture.isCapturing()) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        // Only capture our own block breaking (like ReplayMod)
        if (breakerId != player.id) return

        try {
            val packet = BlockBreakingProgressS2CPacket(breakerId, pos, progress)
            injectPacket(packet)
        } catch (_: Exception) {}
    }

    /**
     * Inject a synthetic packet into the RawPacketCapture queue.
     * Serializes the packet to bytes and stores it with the current tick/timestamp.
     */
    private fun injectPacket(packet: Packet<*>, buf: PacketByteBuf? = null) {
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
