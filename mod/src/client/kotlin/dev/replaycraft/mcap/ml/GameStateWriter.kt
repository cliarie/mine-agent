package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.registry.RegistryKeys
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Writes fixed-size binary records to gamestate.bin, one per tick.
 *
 * Record layout (big-endian, 68 bytes):
 *   tick:          Int32   (4)   offset 0
 *   timestamp_ms:  Int64   (8)   offset 4
 *   player_x:      Float32 (4)   offset 12
 *   player_y:      Float32 (4)   offset 16
 *   player_z:      Float32 (4)   offset 20
 *   player_yaw:    Float32 (4)   offset 24
 *   player_pitch:  Float32 (4)   offset 28
 *   health:        Float32 (4)   offset 32
 *   hunger:        Int32   (4)   offset 36
 *   xp:            Int32   (4)   offset 40
 *   biome_id:      Int16   (2)   offset 44
 *   light_level:   Int8    (1)   offset 46
 *   is_raining:    Int8    (1)   offset 47
 *   time_of_day:   Int32   (4)   offset 48
 *   key_mask:      Int32   (4)   offset 52
 *   yaw_delta:     Float32 (4)   offset 56
 *   pitch_delta:   Float32 (4)   offset 60
 *   dimension:     Int8    (1)   offset 64   0=overworld, 1=nether, 2=end, -1=unknown
 *   player_pose:   Int8    (1)   offset 65   0=standing, 1=sneaking, 2=swimming, 3=crawling, 4=sleeping, 5=elytra
 *   _padding:      Int16   (2)   offset 66   reserved (zero)
 *
 * Total: 68 bytes per record.
 */
class GameStateWriter(private val outputFile: File) {

    companion object {
        const val RECORD_SIZE = 68
        private const val FLUSH_INTERVAL_TICKS = 1000
    }

    private var stream: DataOutputStream? = null
    private var tickCount = 0
    private var previousYaw = Float.NaN
    private var previousPitch = Float.NaN
    private var startTimeMs = 0L

    fun open() {
        outputFile.parentFile?.mkdirs()
        stream = DataOutputStream(BufferedOutputStream(outputFile.outputStream(), 8192))
        startTimeMs = System.currentTimeMillis()
        tickCount = 0
        previousYaw = Float.NaN
        previousPitch = Float.NaN
    }

    /**
     * Write one tick record. Called from ClientTickEvents.END_CLIENT_TICK.
     */
    fun writeTick(client: MinecraftClient, player: ClientPlayerEntity, tick: Int) {
        val out = stream ?: return
        val world = client.world ?: return

        val timestampMs = System.currentTimeMillis() - startTimeMs

        val yaw = player.yaw
        val pitch = player.pitch

        // Compute yaw delta, wrapped to [-180, 180]
        var yawDelta = if (previousYaw.isNaN()) 0.0f else yaw - previousYaw
        while (yawDelta > 180.0f) yawDelta -= 360.0f
        while (yawDelta < -180.0f) yawDelta += 360.0f

        val pitchDelta = if (previousPitch.isNaN()) 0.0f else pitch - previousPitch

        previousYaw = yaw
        previousPitch = pitch

        // Biome raw registry ID (dynamic registry in 1.20.1)
        val blockPos = player.blockPos
        val biomeEntry = world.getBiome(blockPos)
        val biomeRegistry = world.registryManager.get(RegistryKeys.BIOME)
        val biomeId = biomeRegistry.getRawId(biomeEntry.value()).toShort()

        // Light level at player position
        val lightLevel = world.getLightLevel(blockPos).toByte()

        // Rain check
        val isRaining: Byte = if (world.isRaining) 1 else 0

        // Time of day
        val timeOfDay = (world.timeOfDay % 24000).toInt()

        // Key mask bitmask
        val options = client.options
        var keyMask = 0
        if (options.forwardKey.isPressed) keyMask = keyMask or (1 shl 0)
        if (options.backKey.isPressed) keyMask = keyMask or (1 shl 1)
        if (options.leftKey.isPressed) keyMask = keyMask or (1 shl 2)
        if (options.rightKey.isPressed) keyMask = keyMask or (1 shl 3)
        if (options.jumpKey.isPressed) keyMask = keyMask or (1 shl 4)
        if (options.sneakKey.isPressed) keyMask = keyMask or (1 shl 5)
        if (options.sprintKey.isPressed) keyMask = keyMask or (1 shl 6)

        // Hunger and XP
        val hunger = player.hungerManager.foodLevel
        val xp = player.experienceLevel

        // Dimension: 0=overworld, 1=nether, 2=end, -1=unknown
        val dimKey = world.registryKey?.value?.toString() ?: ""
        val dimension: Byte = when (dimKey) {
            "minecraft:overworld" -> 0
            "minecraft:the_nether" -> 1
            "minecraft:the_end" -> 2
            else -> -1
        }

        // Player pose: 0=standing, 1=sneaking, 2=swimming, 3=crawling, 4=sleeping, 5=elytra
        val pose: Byte = when {
            player.isFallFlying -> 5
            player.isSleeping -> 4
            player.isSwimming -> 2
            player.isInSwimmingPose -> 3  // crawling (1-block gap)
            player.isSneaking -> 1
            else -> 0
        }

        // Write the 68-byte record
        out.writeInt(tick)                          // 4 bytes  (offset 0)
        out.writeLong(timestampMs)                  // 8 bytes  (offset 4)
        out.writeFloat(player.x.toFloat())          // 4 bytes  (offset 12)
        out.writeFloat(player.y.toFloat())          // 4 bytes  (offset 16)
        out.writeFloat(player.z.toFloat())          // 4 bytes  (offset 20)
        out.writeFloat(yaw)                         // 4 bytes  (offset 24)
        out.writeFloat(pitch)                       // 4 bytes  (offset 28)
        out.writeFloat(player.health)               // 4 bytes  (offset 32)
        out.writeInt(hunger)                        // 4 bytes  (offset 36)
        out.writeInt(xp)                            // 4 bytes  (offset 40)
        out.writeShort(biomeId.toInt())             // 2 bytes  (offset 44)
        out.writeByte(lightLevel.toInt())           // 1 byte   (offset 46)
        out.writeByte(isRaining.toInt())            // 1 byte   (offset 47)
        out.writeInt(timeOfDay)                     // 4 bytes  (offset 48)
        out.writeInt(keyMask)                       // 4 bytes  (offset 52)
        out.writeFloat(yawDelta)                    // 4 bytes  (offset 56)
        out.writeFloat(pitchDelta)                  // 4 bytes  (offset 60)
        out.writeByte(dimension.toInt())            // 1 byte   (offset 64)
        out.writeByte(pose.toInt())                 // 1 byte   (offset 65)
        out.writeShort(0)                           // 2 bytes  (offset 66) padding

        tickCount++

        // Flush every 1000 ticks (~50 seconds) to avoid data loss on crash
        if (tickCount % FLUSH_INTERVAL_TICKS == 0) {
            out.flush()
        }
    }

    fun close() {
        try {
            stream?.flush()
        } catch (e: Exception) {
            println("[MCAP ML] Error flushing gamestate.bin: ${e.message}")
        }
        try {
            stream?.close()
        } catch (e: Exception) {
            println("[MCAP ML] Error closing gamestate.bin: ${e.message}")
        } finally {
            stream = null
        }
    }
}
