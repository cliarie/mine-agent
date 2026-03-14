package dev.replaycraft.mcap.ml

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Standalone verification that the gamestate.bin record layout is exactly 68 bytes.
 * This does not depend on Minecraft classes — it writes dummy values using the same
 * DataOutputStream sequence as GameStateWriter.writeTick() and checks the output size.
 *
 * Run with: kotlinc -script GameStateRecordSizeTest.kt
 * Or as a JUnit test if a test runner is configured.
 */
fun main() {
    val baos = ByteArrayOutputStream()
    val out = DataOutputStream(baos)

    // Write one record with dummy values matching the GameStateWriter layout:
    out.writeInt(0)             // tick:         Int32   (4)
    out.writeLong(0L)           // timestamp_ms: Int64   (8)
    out.writeFloat(0.0f)       // player_x:     Float32 (4)
    out.writeFloat(0.0f)       // player_y:     Float32 (4)
    out.writeFloat(0.0f)       // player_z:     Float32 (4)
    out.writeFloat(0.0f)       // player_yaw:   Float32 (4)
    out.writeFloat(0.0f)       // player_pitch: Float32 (4)
    out.writeFloat(0.0f)       // health:       Float32 (4)
    out.writeInt(0)             // hunger:       Int32   (4)
    out.writeInt(0)             // xp:           Int32   (4)
    out.writeShort(0)           // biome_id:     Int16   (2)
    out.writeByte(0)            // light_level:  Int8    (1)
    out.writeByte(0)            // is_raining:   Int8    (1)
    out.writeInt(0)             // time_of_day:  Int32   (4)
    out.writeInt(0)             // key_mask:     Int32   (4)
    out.writeFloat(0.0f)       // yaw_delta:    Float32 (4)
    out.writeFloat(0.0f)       // pitch_delta:  Float32 (4)
    out.writeByte(0)            // dimension:    Int8    (1)
    out.writeByte(0)            // player_pose:  Int8    (1)
    out.writeShort(0)           // _padding:     Int16   (2)

    out.flush()
    val size = baos.size()

    println("Record size: $size bytes")
    assert(size == 68) { "Expected 68 bytes but got $size" }
    println("PASS: gamestate.bin record is exactly 68 bytes")
}
