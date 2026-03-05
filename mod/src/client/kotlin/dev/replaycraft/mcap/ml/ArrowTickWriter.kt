package dev.replaycraft.mcap.ml

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
 * Writes tick-level game state to Arrow IPC files.
 *
 * Buffers tick records in memory and flushes to disk periodically
 * (every FLUSH_INTERVAL ticks). Uses Apache Arrow's vector API with
 * arrow-memory-unsafe allocator to avoid Netty conflicts.
 *
 * Output: tick_stream.arrow — one row per game tick (20Hz).
 * Python reads via: pyarrow.ipc.open_file("tick_stream.arrow")
 */
class ArrowTickWriter(private val outputFile: File) {

    companion object {
        /** Flush to disk every N ticks (~50 seconds of gameplay) */
        const val FLUSH_INTERVAL = 1000

        /** Build the Arrow schema for tick_stream */
        fun buildSchema(): Schema = Schema(listOf(
            // Tick identification
            Field("tick", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("timestamp_ms", FieldType.notNullable(ArrowType.Int(64, true)), null),
            // Player position (float64 for precision)
            Field("x", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            Field("y", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            Field("z", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            // Player rotation
            Field("yaw", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            Field("pitch", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            Field("head_yaw", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            // Player state
            Field("health", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            Field("hunger", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("saturation", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            Field("experience_level", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("experience_progress", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)), null),
            Field("is_on_ground", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("is_sprinting", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("is_sneaking", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("is_swimming", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("is_touching_water", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            // Velocity
            Field("vel_x", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            Field("vel_y", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            Field("vel_z", FieldType.notNullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null),
            // Input
            Field("keyboard_mask", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("mouse_buttons", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("hotbar_slot", FieldType.notNullable(ArrowType.Int(32, true)), null),
            // World state
            Field("biome_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
            Field("block_light", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("sky_light", FieldType.notNullable(ArrowType.Int(32, true)), null),
            Field("is_raining", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("is_thundering", FieldType.notNullable(ArrowType.Bool.INSTANCE), null),
            Field("time_of_day", FieldType.notNullable(ArrowType.Int(64, true)), null),
            Field("day_time", FieldType.notNullable(ArrowType.Int(64, true)), null),
            // Screen
            Field("screen_type", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
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

    /**
     * Open the output file and prepare the Arrow writer.
     */
    fun open() {
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        writer = ArrowFileWriter(root, null, Channels.newChannel(fos))
        writer!!.start()
    }

    /**
     * Add a tick record to the buffer. Automatically flushes when buffer is full.
     */
    fun addTick(record: TickRecord) {
        if (closed) return

        val idx = bufferIndex

        // Grow vectors if needed
        if (idx == 0) {
            root.allocateNew()
        }

        // Write each field into the corresponding Arrow vector
        (root.getVector("tick") as IntVector).setSafe(idx, record.tick)
        (root.getVector("timestamp_ms") as BigIntVector).setSafe(idx, record.timestampMs)
        (root.getVector("x") as Float8Vector).setSafe(idx, record.x)
        (root.getVector("y") as Float8Vector).setSafe(idx, record.y)
        (root.getVector("z") as Float8Vector).setSafe(idx, record.z)
        (root.getVector("yaw") as Float4Vector).setSafe(idx, record.yaw)
        (root.getVector("pitch") as Float4Vector).setSafe(idx, record.pitch)
        (root.getVector("head_yaw") as Float4Vector).setSafe(idx, record.headYaw)
        (root.getVector("health") as Float4Vector).setSafe(idx, record.health)
        (root.getVector("hunger") as IntVector).setSafe(idx, record.hunger)
        (root.getVector("saturation") as Float4Vector).setSafe(idx, record.saturation)
        (root.getVector("experience_level") as IntVector).setSafe(idx, record.experienceLevel)
        (root.getVector("experience_progress") as Float4Vector).setSafe(idx, record.experienceProgress)
        (root.getVector("is_on_ground") as BitVector).setSafe(idx, if (record.isOnGround) 1 else 0)
        (root.getVector("is_sprinting") as BitVector).setSafe(idx, if (record.isSprinting) 1 else 0)
        (root.getVector("is_sneaking") as BitVector).setSafe(idx, if (record.isSneaking) 1 else 0)
        (root.getVector("is_swimming") as BitVector).setSafe(idx, if (record.isSwimming) 1 else 0)
        (root.getVector("is_touching_water") as BitVector).setSafe(idx, if (record.isTouchingWater) 1 else 0)
        (root.getVector("vel_x") as Float8Vector).setSafe(idx, record.velX)
        (root.getVector("vel_y") as Float8Vector).setSafe(idx, record.velY)
        (root.getVector("vel_z") as Float8Vector).setSafe(idx, record.velZ)
        (root.getVector("keyboard_mask") as IntVector).setSafe(idx, record.keyboardMask)
        (root.getVector("mouse_buttons") as IntVector).setSafe(idx, record.mouseButtons)
        (root.getVector("hotbar_slot") as IntVector).setSafe(idx, record.hotbarSlot)
        (root.getVector("biome_id") as VarCharVector).setSafe(idx, record.biomeId.toByteArray(Charsets.UTF_8))
        (root.getVector("block_light") as IntVector).setSafe(idx, record.blockLight)
        (root.getVector("sky_light") as IntVector).setSafe(idx, record.skyLight)
        (root.getVector("is_raining") as BitVector).setSafe(idx, if (record.isRaining) 1 else 0)
        (root.getVector("is_thundering") as BitVector).setSafe(idx, if (record.isThundering) 1 else 0)
        (root.getVector("time_of_day") as BigIntVector).setSafe(idx, record.timeOfDay)
        (root.getVector("day_time") as BigIntVector).setSafe(idx, record.dayTime)
        (root.getVector("screen_type") as VarCharVector).setSafe(idx, record.screenType.toByteArray(Charsets.UTF_8))

        bufferIndex++
        totalRows++

        // Flush when buffer reaches interval
        if (bufferIndex >= FLUSH_INTERVAL) {
            flush()
        }
    }

    /**
     * Flush buffered records to disk as an Arrow record batch.
     */
    fun flush() {
        if (bufferIndex == 0 || closed) return

        try {
            root.setRowCount(bufferIndex)
            writer?.writeBatch()
        } catch (e: Exception) {
            println("[MCAP-ML] Error flushing Arrow tick batch: ${e.message}")
        }

        bufferIndex = 0
        root.allocateNew()
    }

    /**
     * Flush remaining data and close the writer.
     * Returns total number of rows written.
     */
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
            println("[MCAP-ML] Error closing Arrow tick writer: ${e.message}")
        } finally {
            root.close()
            allocator.close()
        }

        return totalRows
    }

    /** Get total rows written so far */
    fun getTotalRows(): Int = totalRows
}
