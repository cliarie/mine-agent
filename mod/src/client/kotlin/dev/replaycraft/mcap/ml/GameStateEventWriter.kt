package dev.replaycraft.mcap.ml

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.registry.Registries
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Writes variable-length event records to gamestate_events.bin.
 *
 * Currently tracks inventory changes. Each record:
 *   tick:           Int32  (4)
 *   event_type:     Int8   (1)   0=INVENTORY_DELTA, 1=CRAFTED, 2=PLAYER_DIED, 3=SLEPT,
 *                                4=DIMENSION_CHANGE, 5=DRAGON_HEALTH, 6=EQUIPMENT_CHANGE,
 *                                7=ADVANCEMENT
 *   item_name_len:  Int8   (1)
 *   item_name:      UTF-8 bytes (variable)
 *   count:          Int16  (2)   current count (0 if removed)
 *   prev_count:     Int16  (2)   previous count (0 if first acquisition)
 */
class GameStateEventWriter(private val outputFile: File) {

    companion object {
        const val EVENT_INVENTORY_DELTA: Byte = 0
        const val EVENT_CRAFTED: Byte = 1
        const val EVENT_PLAYER_DIED: Byte = 2
        const val EVENT_SLEPT: Byte = 3
        const val EVENT_DIMENSION_CHANGE: Byte = 4
        const val EVENT_DRAGON_HEALTH: Byte = 5
        const val EVENT_EQUIPMENT_CHANGE: Byte = 6
        const val EVENT_ADVANCEMENT: Byte = 7
    }

    private var stream: DataOutputStream? = null

    /** Aggregated inventory: item registry path -> total count across all slots */
    private var previousInventory: MutableMap<String, Int> = mutableMapOf()

    fun open() {
        outputFile.parentFile?.mkdirs()
        stream = DataOutputStream(BufferedOutputStream(outputFile.outputStream(), 4096))
        previousInventory.clear()
    }

    /**
     * Snapshot the player's inventory and emit delta events for any changes.
     * Called every tick from the tick listener.
     */
    fun checkInventory(player: ClientPlayerEntity, tick: Int) {
        val out = stream ?: return

        // Build current aggregated inventory
        val currentInventory = mutableMapOf<String, Int>()
        val inventory = player.inventory

        // Main inventory (0-35) + offhand (40)
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (!stack.isEmpty) {
                val itemPath = Registries.ITEM.getId(stack.item).path
                currentInventory[itemPath] = (currentInventory[itemPath] ?: 0) + stack.count
            }
        }

        // Compare with previous snapshot
        val allKeys = (previousInventory.keys + currentInventory.keys).toSet()
        for (key in allKeys) {
            val prevCount = previousInventory[key] ?: 0
            val currCount = currentInventory[key] ?: 0

            if (prevCount != currCount) {
                writeInventoryDelta(out, tick, key, currCount, prevCount)
            }
        }

        previousInventory = currentInventory
    }

    /**
     * Write a PLAYER_DIED event.
     */
    fun writePlayerDied(tick: Int) {
        val out = stream ?: return
        val nameBytes = "player".toByteArray(Charsets.UTF_8)
        out.writeInt(tick)
        out.writeByte(EVENT_PLAYER_DIED.toInt())
        out.writeByte(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(0)
        out.writeShort(0)
    }

    /**
     * Write a SLEPT event.
     */
    fun writeSlept(tick: Int) {
        val out = stream ?: return
        val nameBytes = "bed".toByteArray(Charsets.UTF_8)
        out.writeInt(tick)
        out.writeByte(EVENT_SLEPT.toInt())
        out.writeByte(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(0)
        out.writeShort(0)
    }

    /**
     * Write a CRAFTED event when the player crafts an item.
     */
    fun writeCrafted(tick: Int, itemName: String, count: Int) {
        val out = stream ?: return
        val nameBytes = itemName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)
        out.writeInt(tick)
        out.writeByte(EVENT_CRAFTED.toInt())
        out.writeByte(nameLen)
        out.write(nameBytes, 0, nameLen)
        out.writeShort(count)
        out.writeShort(0)
    }

    /**
     * Write a DIMENSION_CHANGE event.
     */
    fun writeDimensionChange(tick: Int, dimensionId: String) {
        val out = stream ?: return
        val nameBytes = dimensionId.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)
        out.writeInt(tick)
        out.writeByte(EVENT_DIMENSION_CHANGE.toInt())
        out.writeByte(nameLen)
        out.write(nameBytes, 0, nameLen)
        out.writeShort(0)
        out.writeShort(0)
    }

    /**
     * Write a DRAGON_HEALTH event (health as fixed-point in count field).
     */
    fun writeDragonHealth(tick: Int, healthPercent: Float) {
        val out = stream ?: return
        val nameBytes = "ender_dragon".toByteArray(Charsets.UTF_8)
        out.writeInt(tick)
        out.writeByte(EVENT_DRAGON_HEALTH.toInt())
        out.writeByte(nameBytes.size)
        out.write(nameBytes)
        out.writeShort((healthPercent * 100).toInt().coerceIn(0, 10000))
        out.writeShort(0)
    }

    /**
     * Write an EQUIPMENT_CHANGE event for a slot change.
     * slot: 0=mainhand, 1=offhand, 2=boots, 3=leggings, 4=chestplate, 5=helmet
     */
    fun writeEquipmentChange(tick: Int, slotName: String, itemName: String, enchanted: Boolean) {
        val out = stream ?: return
        val fullName = "$slotName:$itemName${if (enchanted) ":ench" else ""}"
        val nameBytes = fullName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)
        out.writeInt(tick)
        out.writeByte(EVENT_EQUIPMENT_CHANGE.toInt())
        out.writeByte(nameLen)
        out.write(nameBytes, 0, nameLen)
        out.writeShort(1)
        out.writeShort(0)
    }

    /**
     * Write an ADVANCEMENT event.
     */
    fun writeAdvancement(tick: Int, advancementId: String) {
        val out = stream ?: return
        val nameBytes = advancementId.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)
        out.writeInt(tick)
        out.writeByte(EVENT_ADVANCEMENT.toInt())
        out.writeByte(nameLen)
        out.write(nameBytes, 0, nameLen)
        out.writeShort(0)
        out.writeShort(0)
    }

    private fun writeInventoryDelta(
        out: DataOutputStream,
        tick: Int,
        itemName: String,
        count: Int,
        prevCount: Int
    ) {
        val nameBytes = itemName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)

        out.writeInt(tick)                          // 4 bytes
        out.writeByte(EVENT_INVENTORY_DELTA.toInt()) // 1 byte
        out.writeByte(nameLen)                       // 1 byte
        out.write(nameBytes, 0, nameLen)             // variable
        out.writeShort(count)                        // 2 bytes
        out.writeShort(prevCount)                    // 2 bytes
    }

    fun close() {
        try {
            stream?.flush()
        } catch (e: Exception) {
            println("[MCAP ML] Error flushing gamestate_events.bin: ${e.message}")
        }
        try {
            stream?.close()
        } catch (e: Exception) {
            println("[MCAP ML] Error closing gamestate_events.bin: ${e.message}")
        } finally {
            stream = null
        }
    }
}
