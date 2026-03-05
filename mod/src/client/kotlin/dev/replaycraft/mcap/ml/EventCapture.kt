package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels

/**
 * Captures discrete events (inventory deltas, block breaks, deaths, etc.)
 * and writes them to an Arrow IPC events.arrow file.
 *
 * Inventory is NOT serialized every tick — only deltas are emitted when slots
 * change. On the Python side, full inventory state at any tick is reconstructed
 * by replaying deltas. This also makes the event stream the ground truth for
 * subgoal detection (first appearance of item_id).
 */
class EventCapture(private val outputFile: File) {

    companion object {
        const val FLUSH_INTERVAL = 500

        fun buildSchema(): Schema = Schema(listOf(
            Field("tick", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("timestamp_ms", FieldType.notNullable(ArrowType.Int(64, true)), null),
            Field("event_type", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            // For INVENTORY_DELTA events
            Field("slot", FieldType.nullable(ArrowType.Int(32, true)), null),
            Field("item_id", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
            Field("count", FieldType.nullable(ArrowType.Int(32, true)), null),
            Field("prev_count", FieldType.nullable(ArrowType.Int(32, true)), null),
            // For positional events (block break, death, etc.)
            Field("block_x", FieldType.nullable(ArrowType.Int(32, true)), null),
            Field("block_y", FieldType.nullable(ArrowType.Int(32, true)), null),
            Field("block_z", FieldType.nullable(ArrowType.Int(32, true)), null),
            Field("block_id", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
            // Generic detail field
            Field("detail", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
        ))
    }

    private val allocator = RootAllocator(Long.MAX_VALUE)
    private val schema = buildSchema()
    private val root = VectorSchemaRoot.create(schema, allocator)
    private var writer: ArrowFileWriter? = null
    private var fos: FileOutputStream? = null
    private var bufferIndex = 0
    private var totalRows = 0
    private var closed = false

    // Inventory tracking for delta detection
    private var trackedSlots: Array<SlotState>? = null

    private data class SlotState(val itemId: String, val count: Int)

    fun open() {
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        writer = ArrowFileWriter(root, null, Channels.newChannel(fos))
        writer!!.start()
    }

    /**
     * Called each tick to detect inventory changes and emit deltas.
     */
    fun onTick(tick: Int, player: PlayerEntity) {
        if (closed) return
        trackInventoryDeltas(tick, player)
    }

    /**
     * Emit a generic event (block break, death, screen open, etc.)
     */
    fun emitEvent(
        tick: Int,
        eventType: String,
        blockX: Int? = null,
        blockY: Int? = null,
        blockZ: Int? = null,
        blockId: String? = null,
        detail: String? = null,
    ) {
        if (closed) return
        writeRow(tick, eventType, null, null, null, null, blockX, blockY, blockZ, blockId, detail)
    }

    /**
     * Track inventory slots and emit INVENTORY_DELTA events for changes.
     */
    private fun trackInventoryDeltas(tick: Int, player: PlayerEntity) {
        try {
            val inv = player.inventory
            val totalSlots = inv.main.size + inv.armor.size + inv.offHand.size

            if (trackedSlots == null) {
                // First tick: initialize tracking and emit initial state as deltas from empty
                trackedSlots = Array(totalSlots) { i ->
                    val stack = getSlotStack(inv, i)
                    val itemId = getItemId(stack)
                    val count = stack.count
                    if (!stack.isEmpty) {
                        writeRow(tick, "INVENTORY_DELTA", i, itemId, count, 0, null, null, null, null, null)
                    }
                    SlotState(itemId, count)
                }
                return
            }

            val tracked = trackedSlots!!
            for (i in 0 until minOf(totalSlots, tracked.size)) {
                val stack = getSlotStack(inv, i)
                val itemId = getItemId(stack)
                val count = stack.count

                if (tracked[i].itemId != itemId || tracked[i].count != count) {
                    val prevCount = tracked[i].count
                    tracked[i] = SlotState(itemId, count)
                    writeRow(tick, "INVENTORY_DELTA", i, itemId, count, prevCount, null, null, null, null, null)
                }
            }
        } catch (_: Exception) {
            // Ignore inventory tracking errors
        }
    }

    private fun getSlotStack(inv: net.minecraft.entity.player.PlayerInventory, index: Int): ItemStack {
        return when {
            index < inv.main.size -> inv.main[index]
            index < inv.main.size + inv.armor.size -> inv.armor[index - inv.main.size]
            else -> inv.offHand[index - inv.main.size - inv.armor.size]
        }
    }

    private fun getItemId(stack: ItemStack): String {
        if (stack.isEmpty) return "air"
        return Registries.ITEM.getId(stack.item).toString()
    }

    private fun writeRow(
        tick: Int,
        eventType: String,
        slot: Int?,
        itemId: String?,
        count: Int?,
        prevCount: Int?,
        blockX: Int?,
        blockY: Int?,
        blockZ: Int?,
        blockId: String?,
        detail: String?,
    ) {
        val idx = bufferIndex
        if (idx == 0) {
            root.allocateNew()
        }

        (root.getVector("tick") as IntVector).setSafe(idx, tick)
        (root.getVector("timestamp_ms") as BigIntVector).setSafe(idx, System.currentTimeMillis())
        (root.getVector("event_type") as VarCharVector).setSafe(idx, eventType.toByteArray(Charsets.UTF_8))

        setNullableInt(root.getVector("slot") as IntVector, idx, slot)
        setNullableVarChar(root.getVector("item_id") as VarCharVector, idx, itemId)
        setNullableInt(root.getVector("count") as IntVector, idx, count)
        setNullableInt(root.getVector("prev_count") as IntVector, idx, prevCount)
        setNullableInt(root.getVector("block_x") as IntVector, idx, blockX)
        setNullableInt(root.getVector("block_y") as IntVector, idx, blockY)
        setNullableInt(root.getVector("block_z") as IntVector, idx, blockZ)
        setNullableVarChar(root.getVector("block_id") as VarCharVector, idx, blockId)
        setNullableVarChar(root.getVector("detail") as VarCharVector, idx, detail)

        bufferIndex++
        totalRows++

        if (bufferIndex >= FLUSH_INTERVAL) {
            flush()
        }
    }

    private fun setNullableInt(vec: IntVector, idx: Int, value: Int?) {
        if (value != null) vec.setSafe(idx, value) else vec.setNull(idx)
    }

    private fun setNullableVarChar(vec: VarCharVector, idx: Int, value: String?) {
        if (value != null) vec.setSafe(idx, value.toByteArray(Charsets.UTF_8)) else vec.setNull(idx)
    }

    fun flush() {
        if (bufferIndex == 0 || closed) return
        try {
            root.setRowCount(bufferIndex)
            writer?.writeBatch()
        } catch (e: Exception) {
            println("[MCAP-ML] Error flushing Arrow event batch: ${e.message}")
        }
        bufferIndex = 0
        root.allocateNew()
    }

    fun close(): Int {
        if (closed) return totalRows
        closed = true
        try {
            if (bufferIndex > 0) {
                root.setRowCount(bufferIndex)
                writer?.writeBatch()
            }
            writer?.end()
            writer?.close()
            fos?.close()
        } catch (e: Exception) {
            println("[MCAP-ML] Error closing Arrow event writer: ${e.message}")
        } finally {
            root.close()
            allocator.close()
        }
        return totalRows
    }

    fun getTotalRows(): Int = totalRows

    /**
     * Reset inventory tracking state (call when session changes).
     */
    fun resetTracking() {
        trackedSlots = null
    }
}
