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
 *   event_type:     Int8   (1)   0=INVENTORY_DELTA, 1=CRAFTED, 2=PLAYER_DIED, 3=SLEPT
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
            stream?.close()
        } catch (e: Exception) {
            println("[MCAP ML] Error closing gamestate_events.bin: ${e.message}")
        } finally {
            stream = null
        }
    }
}
