package dev.replaycraft.mcap.capture

import io.netty.buffer.Unpooled
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.play.*
import net.minecraft.util.Hand
import net.minecraft.util.collection.DefaultedList
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
 *
 * Additionally, spawnRecordingPlayer() injects spawn packets for the local player
 * (matching ReplayMod's RecordingEventHandler.spawnRecordingPlayer()), and
 * spawnExistingEntities() injects spawn packets for all entities already loaded
 * in the world when recording starts. Without these, creatures and the player
 * won't appear during replay because the server never re-sends spawn packets
 * for entities that were already tracked.
 */
object RecordingEventHandler {

    private var hasSpawnedPlayer = false
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

    // Inventory slot tracking for capturing item movements.
    // PlayerScreenHandler has 46 slots: 0=crafting output, 1-4=crafting input,
    // 5-8=armor, 9-35=main inventory, 36-44=hotbar, 45=offhand.
    private var trackedInventory: Array<ItemStack>? = null
    private var trackedCursorStack: ItemStack = ItemStack.EMPTY

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
        hasSpawnedPlayer = false
        playerItems.fill(ItemStack.EMPTY)
        trackedInventory = null
        trackedCursorStack = ItemStack.EMPTY
        trackedContainerSyncId = -1
        trackedContainerSlots = null
    }

    /**
     * Inject a PlayerSpawnS2CPacket + EntityTrackerUpdateS2CPacket for the local player.
     * Matching ReplayMod's RecordingEventHandler.spawnRecordingPlayer().
     *
     * The server never sends a spawn packet for the local player to itself,
     * but during replay the player entity needs to be spawned as a third-person
     * entity so it's visible and tracked correctly.
     */
    fun spawnRecordingPlayer() {
        if (!RawPacketCapture.isCapturing()) return
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        try {
            // PlayerSpawnS2CPacket for the local player (like ReplayMod line 139)
            injectPacket(PlayerSpawnS2CPacket(player))

            // EntityTrackerUpdateS2CPacket with all tracked data (like ReplayMod line 144)
            val entries = player.dataTracker.changedEntries
            if (entries != null && entries.isNotEmpty()) {
                injectPacket(EntityTrackerUpdateS2CPacket(player.id, entries))
            }
        } catch (e: Exception) {
            println("[MCAP] Failed to inject player spawn: ${e.message}")
        }
    }

    /**
     * Inject spawn packets for all entities currently loaded in the world.
     * This ensures creatures (cows, pigs, villagers, creepers, etc.) that were
     * already present when recording started appear during replay.
     *
     * The server only sends EntitySpawnS2CPacket when an entity first enters
     * the player's tracking range. If recording starts after entities are already
     * tracked, those spawn packets were missed. We inject them synthetically.
     */
    fun spawnExistingEntities() {
        if (!RawPacketCapture.isCapturing()) return
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val player = client.player ?: return

        var count = 0
        for (entity in world.entities) {
            if (entity == player) continue // Player is handled by spawnRecordingPlayer
            if (entity.id == player.id) continue

            try {
                if (entity is PlayerEntity) {
                    // Other players use PlayerSpawnS2CPacket
                    injectPacket(PlayerSpawnS2CPacket(entity))
                } else {
                    // All other entities (mobs, animals, items, etc.) use EntitySpawnS2CPacket
                    injectPacket(EntitySpawnS2CPacket(entity))
                }

                // Send tracker data so entity appearance/state is correct
                val entries = entity.dataTracker.changedEntries
                if (entries != null && entries.isNotEmpty()) {
                    injectPacket(EntityTrackerUpdateS2CPacket(entity.id, entries))
                }

                // Send equipment for living entities
                if (entity is LivingEntity) {
                    for (slot in EquipmentSlot.values()) {
                        val stack = entity.getEquippedStack(slot)
                        if (!stack.isEmpty) {
                            injectPacket(EntityEquipmentUpdateS2CPacket(
                                entity.id,
                                listOf(com.mojang.datafixers.util.Pair(slot, stack.copy()))
                            ))
                        }
                    }
                }

                count++
            } catch (e: Exception) {
                // Skip entities that fail to serialize
            }
        }
        println("[MCAP] Injected spawn packets for $count existing entities")
    }

    /**
     * Called each world tick to inject synthetic packets for the local player.
     */
    fun onPlayerTick() {
        if (!RawPacketCapture.isCapturing()) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        // On first tick, inject spawn packets for all existing entities (creatures).
        // We do NOT call spawnRecordingPlayer() because this is a first-person replay:
        // the local player already exists from GameJoinS2CPacket and we don't want
        // a visible third-person player model blocking the first-person view.
        if (!hasSpawnedPlayer) {
            hasSpawnedPlayer = true
            spawnExistingEntities()
            injectInventorySnapshot(player)
        }

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

        if (force || Math.abs(dx) > maxRelDist || Math.abs(dy) > maxRelDist || Math.abs(dz) > maxRelDist) {
            // Absolute teleport packet
            val posBuf = PacketByteBuf(Unpooled.buffer())
            try {
                posBuf.writeVarInt(player.id)
                posBuf.writeDouble(x)
                posBuf.writeDouble(y)
                posBuf.writeDouble(z)
                posBuf.writeByte((player.yaw * 256.0f / 360.0f).toInt())
                posBuf.writeByte((player.pitch * 256.0f / 360.0f).toInt())
                posBuf.writeBoolean(player.isOnGround)
                val posPacket = EntityPositionS2CPacket(posBuf)
                injectPacket(posPacket, posBuf)
            } catch (_: Exception) {
            } finally {
                posBuf.release()
            }
        } else {
            // Relative movement + rotation packet (matches ReplayMod)
            try {
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
            } catch (_: Exception) {}
        }

        // --- Velocity packet ---
        val vel = player.velocity
        if (vel.lengthSquared() > 0.0001) {
            val velBuf = PacketByteBuf(Unpooled.buffer())
            try {
                velBuf.writeVarInt(player.id)
                velBuf.writeShort((vel.x.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                velBuf.writeShort((vel.y.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                velBuf.writeShort((vel.z.coerceIn(-3.9, 3.9) * 8000.0).toInt())
                val velPacket = EntityVelocityUpdateS2CPacket(velBuf)
                injectPacket(velPacket, velBuf)
            } catch (_: Exception) {
            } finally {
                velBuf.release()
            }
        }

        // --- Head yaw (only if changed, like ReplayMod) ---
        val rotationYawHead = (player.headYaw * 256.0f / 360.0f).toInt()
        if (rotationYawHead != lastRotationYawHead) {
            val headBuf = PacketByteBuf(Unpooled.buffer())
            try {
                headBuf.writeVarInt(player.id)
                headBuf.writeByte(rotationYawHead)
                val headPacket = EntitySetHeadYawS2CPacket(headBuf)
                injectPacket(headPacket, headBuf)
            } catch (_: Exception) {
            } finally {
                headBuf.release()
            }
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
        // Use the Entity constructor like ReplayMod does: EntityAnimationS2CPacket(player, animId)
        // 0 = SWING_MAIN_HAND, 3 = SWING_OFF_HAND
        if (player.handSwinging && player.handSwingTicks == 0) {
            try {
                val animType = if (player.preferredHand == Hand.MAIN_HAND) 0 else 3
                val animPacket = EntityAnimationS2CPacket(player, animType)
                injectPacket(animPacket)
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
        // Use Entity constructor: EntityAnimationS2CPacket(player, 2) for WAKE_UP
        if (!player.isSleeping && wasSleeping) {
            try {
                val animPacket = EntityAnimationS2CPacket(player, 2)
                injectPacket(animPacket)
            } catch (_: Exception) {}
            wasSleeping = false
        }
        if (player.isSleeping) {
            wasSleeping = true
        }

        // --- Inventory slot tracking ---
        // Capture item movements in both client-side inventory (E key) and
        // server-mediated containers. The server sends ScreenHandlerSlotUpdateS2CPacket
        // for server-mediated changes, but client-side inventory manipulations
        // (especially in singleplayer) may not generate visible S2C packets in
        // the capture pipeline. We inject synthetic slot updates for any changes.
        captureInventoryChanges(player)
    }

    /**
     * Inject a full inventory snapshot as InventoryS2CPacket.
     * Called at recording start so replay has the initial inventory state.
     */
    private fun injectInventorySnapshot(player: PlayerEntity) {
        try {
            val handler = player.playerScreenHandler
            val slotCount = handler.slots.size
            val contents = DefaultedList.ofSize(slotCount, ItemStack.EMPTY)
            for (i in 0 until slotCount) {
                contents[i] = handler.slots[i].stack.copy()
            }
            val cursorStack = handler.cursorStack.copy()
            injectPacket(InventoryS2CPacket(handler.syncId, 0, contents, cursorStack))

            // Initialize tracking state
            trackedInventory = Array(slotCount) { i -> handler.slots[i].stack.copy() }
            trackedCursorStack = cursorStack
        } catch (e: Exception) {
            println("[MCAP] Failed to inject inventory snapshot: ${e.message}")
        }
    }

    /**
     * Compare current inventory with tracked state and inject
     * ScreenHandlerSlotUpdateS2CPacket for any changed slots.
     * This captures item movements in real-time during recording.
     */
    private fun captureInventoryChanges(player: PlayerEntity) {
        try {
            val handler = player.playerScreenHandler
            val tracked = trackedInventory ?: return // Not yet initialized
            val slotCount = handler.slots.size

            // Check each slot for changes
            for (i in 0 until minOf(slotCount, tracked.size)) {
                val current = handler.slots[i].stack
                if (!ItemStack.areEqual(tracked[i], current)) {
                    tracked[i] = current.copy()
                    injectPacket(ScreenHandlerSlotUpdateS2CPacket(
                        handler.syncId, 0, i, current.copy()
                    ))
                }
            }

            // Check cursor stack (item held by mouse)
            val currentCursor = handler.cursorStack
            if (!ItemStack.areEqual(trackedCursorStack, currentCursor)) {
                trackedCursorStack = currentCursor.copy()
                injectPacket(ScreenHandlerSlotUpdateS2CPacket(
                    ScreenHandlerSlotUpdateS2CPacket.UPDATE_CURSOR_SYNC_ID,
                    0, 0, currentCursor.copy()
                ))
            }

            // Also track any open container's slot changes
            val currentScreen = MinecraftClient.getInstance().currentScreen
            if (currentScreen is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                val screenHandler = currentScreen.screenHandler
                if (screenHandler != handler) {
                    // Different screen handler (chest, crafting table, etc.)
                    captureContainerChanges(screenHandler)
                }
            }
        } catch (_: Exception) {
            // Ignore inventory tracking errors
        }
    }

    // Track open container slots separately
    private var trackedContainerSyncId: Int = -1
    private var trackedContainerSlots: Array<ItemStack>? = null

    /**
     * Track slot changes in server-mediated containers (chests, crafting tables, etc.).
     * The server sends slot updates via packets, but we inject extras to ensure
     * completeness — especially for the initial state when a container first opens.
     */
    private fun captureContainerChanges(handler: net.minecraft.screen.ScreenHandler) {
        try {
            val syncId = handler.syncId
            if (syncId != trackedContainerSyncId || trackedContainerSlots == null) {
                // New container opened — inject full contents
                trackedContainerSyncId = syncId
                val slotCount = handler.slots.size
                trackedContainerSlots = Array(slotCount) { i -> handler.slots[i].stack.copy() }

                // Inject full container contents
                val contents = DefaultedList.ofSize(slotCount, ItemStack.EMPTY)
                for (i in 0 until slotCount) {
                    contents[i] = handler.slots[i].stack.copy()
                }
                injectPacket(InventoryS2CPacket(syncId, 0, contents, handler.cursorStack.copy()))
                return
            }

            // Check for slot changes in the container
            val tracked = trackedContainerSlots ?: return
            val slotCount = handler.slots.size
            for (i in 0 until minOf(slotCount, tracked.size)) {
                val current = handler.slots[i].stack
                if (!ItemStack.areEqual(tracked[i], current)) {
                    tracked[i] = current.copy()
                    injectPacket(ScreenHandlerSlotUpdateS2CPacket(
                        syncId, 0, i, current.copy()
                    ))
                }
            }
        } catch (_: Exception) {
            // Ignore container tracking errors
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
