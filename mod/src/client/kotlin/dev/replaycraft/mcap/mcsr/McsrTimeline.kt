package dev.replaycraft.mcap.mcsr

import java.nio.ByteBuffer

/**
 * MCSR Ranked 5.7.3 timeline data model and binary deserializer.
 *
 * Timeline data format (per TimeLinePackage):
 *   byte type (ordinal of TimeLineType enum)
 *   int  tick
 *   [variable-length timeline-specific bytes]
 *
 * The timeline-specific bytes follow a class hierarchy where each level
 * appends its fields (big-endian, Java default ByteBuffer order):
 *
 *   TimeLine        → (nothing)
 *   WorldTimeLine   → byte worldOrdinal
 *   PositionFTimeLine → byte world, float x, float y, float z
 *   PositionFRotationTimeLine → byte world, float x, float y, float z, short yaw, short pitch
 *   RotationTimeLine → byte world, short yaw, short pitch
 *   PositionITimeLine → byte world, long rawBlockPos
 *   EntityTimeLine  → byte world, float x, float y, float z, short yaw, short pitch, int entityId
 */

/** Mirrors MCSR's WorldTypes enum (ordinal order). */
enum class McsrWorldType {
    OVERWORLD, NETHER, END;

    companion object {
        fun fromOrdinal(b: Int): McsrWorldType = entries.getOrElse(b) { OVERWORLD }
    }
}

/**
 * Mirrors MCSR's TimeLineType enum (ordinal order).
 * The ordinal is used as the byte tag in the binary format.
 */
enum class McsrTimelineType {
    EMPTY,
    PLAYER_POSITION,
    PLAYER_POSITION_LOOK,
    PLAYER_POSITION_POS,
    PLAYER_SPAWN,
    BLOCK_UPDATE_V1,
    BLOCK_BREAK,
    BLOCK_REMOVE,
    ENTITY_ADD,
    ENTITY_REMOVE,
    ENTITY_MOVE,
    ENTITY_MOVE_LOOK,
    ENTITY_MOVE_POS,
    ENTITY_DAMAGE,
    PROJECTILE_ENTITY,
    ITEM_EQUIP,
    PLAYER_POSE,
    PLAYER_REMOVE,
    BLOCK_PROGRESS,
    PLAYER_RIDE,
    ITEM_ENTITY,
    DRAGON_HEALTH_UPDATE,
    DRAGON_CRYSTAL_UPDATE,
    DRAGON_MOVE,
    DRAGON_DEATH,
    DEBUG_DRAGON_REFRESH,
    DEBUG_PLAYER_FOLLOW,
    DEBUG_ENTITIES_REFRESH,
    BLOCK_UPDATE_V2,
    EXPLOSION_EFFECT,
    PLAYER_PAUSE,
    CHEST_ANIMATION;

    companion object {
        fun fromOrdinal(b: Int): McsrTimelineType = entries.getOrElse(b) { EMPTY }
    }
}

// ---- Timeline event data classes ----

/** Base for all deserialized timeline events. */
sealed class McsrTimelineEvent(val type: McsrTimelineType, val tick: Int)

class PlayerPositionEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Short, val pitch: Short
) : McsrTimelineEvent(McsrTimelineType.PLAYER_POSITION, tick)

class PlayerPositionLookEvent(
    tick: Int,
    val world: McsrWorldType,
    val yaw: Short, val pitch: Short
) : McsrTimelineEvent(McsrTimelineType.PLAYER_POSITION_LOOK, tick)

class PlayerPositionPosEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float
) : McsrTimelineEvent(McsrTimelineType.PLAYER_POSITION_POS, tick)

class PlayerSpawnEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Short, val pitch: Short
) : McsrTimelineEvent(McsrTimelineType.PLAYER_SPAWN, tick)

class PlayerPoseEvent(
    tick: Int,
    val pose: Byte
) : McsrTimelineEvent(McsrTimelineType.PLAYER_POSE, tick)

class PlayerRemoveEvent(
    tick: Int,
    val world: McsrWorldType,
    val isDeath: Boolean
) : McsrTimelineEvent(McsrTimelineType.PLAYER_REMOVE, tick)

class PlayerRideEvent(
    tick: Int,
    val isRiding: Boolean
) : McsrTimelineEvent(McsrTimelineType.PLAYER_RIDE, tick)

class PlayerPauseEvent(
    tick: Int,
    val isPaused: Boolean
) : McsrTimelineEvent(McsrTimelineType.PLAYER_PAUSE, tick)

class BlockUpdateV1Event(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long,
    val rawBlockState: Int,
    val flags: Byte
) : McsrTimelineEvent(McsrTimelineType.BLOCK_UPDATE_V1, tick)

class BlockUpdateV2Event(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long,
    val rawBlockState: Int,
    val flags: Byte
) : McsrTimelineEvent(McsrTimelineType.BLOCK_UPDATE_V2, tick)

class BlockBreakEvent(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long
) : McsrTimelineEvent(McsrTimelineType.BLOCK_BREAK, tick)

class BlockRemoveEvent(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long
) : McsrTimelineEvent(McsrTimelineType.BLOCK_REMOVE, tick)

class BlockProgressEvent(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long,
    val progress: Byte
) : McsrTimelineEvent(McsrTimelineType.BLOCK_PROGRESS, tick)

class EntityAddEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Short, val pitch: Short,
    val entityId: Int,
    val entityTypeId: Int,
    val customData: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_ADD, tick)

class EntityRemoveEvent(
    tick: Int,
    val world: McsrWorldType,
    val entityId: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_REMOVE, tick)

class EntityMoveEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Short, val pitch: Short,
    val entityId: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_MOVE, tick)

class EntityMoveLookEvent(
    tick: Int,
    val world: McsrWorldType,
    val yaw: Short, val pitch: Short,
    val entityId: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_MOVE_LOOK, tick)

class EntityMovePosEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val entityId: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_MOVE_POS, tick)

class EntityDamageEvent(
    tick: Int,
    val world: McsrWorldType,
    val entityId: Int
) : McsrTimelineEvent(McsrTimelineType.ENTITY_DAMAGE, tick)

class ProjectileEntityEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val yaw: Short, val pitch: Short,
    val entityId: Int,
    val entityTypeId: Int
) : McsrTimelineEvent(McsrTimelineType.PROJECTILE_ENTITY, tick)

class ItemEquipEvent(
    tick: Int,
    val slot: Byte,
    val itemId: Int
) : McsrTimelineEvent(McsrTimelineType.ITEM_EQUIP, tick)

class ItemEntityEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val entityId: Int,
    val itemId: Int
) : McsrTimelineEvent(McsrTimelineType.ITEM_ENTITY, tick)

class DragonHealthUpdateEvent(
    tick: Int,
    val health: Float
) : McsrTimelineEvent(McsrTimelineType.DRAGON_HEALTH_UPDATE, tick)

class DragonCrystalUpdateEvent(
    tick: Int,
    val index: Byte
) : McsrTimelineEvent(McsrTimelineType.DRAGON_CRYSTAL_UPDATE, tick)

class DragonMoveEvent(
    tick: Int,
    val x: Float, val y: Float, val z: Float
) : McsrTimelineEvent(McsrTimelineType.DRAGON_MOVE, tick)

class DragonDeathEvent(tick: Int) : McsrTimelineEvent(McsrTimelineType.DRAGON_DEATH, tick)

class ExplosionEffectEvent(
    tick: Int,
    val world: McsrWorldType,
    val x: Float, val y: Float, val z: Float,
    val radius: Float
) : McsrTimelineEvent(McsrTimelineType.EXPLOSION_EFFECT, tick)

class ChestAnimationEvent(
    tick: Int,
    val world: McsrWorldType,
    val rawBlockPos: Long,
    val open: Boolean
) : McsrTimelineEvent(McsrTimelineType.CHEST_ANIMATION, tick)

/** Fallback for unrecognized or debug-only timeline types. */
class UnknownTimelineEvent(
    type: McsrTimelineType,
    tick: Int
) : McsrTimelineEvent(type, tick)

/**
 * Deserializes a stream of MCSR TimeLinePackage entries from a ByteBuffer.
 *
 * Binary format per package: byte type, int tick, [timeline bytes]
 * Returns events grouped by tick for efficient per-tick playback.
 */
object McsrTimelineDeserializer {

    /**
     * Parse all timeline events from a decrypted/decompressed ByteBuffer.
     * Returns a map of tick → list of events, sorted by tick.
     */
    fun deserialize(buf: ByteBuffer): Map<Int, List<McsrTimelineEvent>> {
        val events = mutableMapOf<Int, MutableList<McsrTimelineEvent>>()
        var count = 0
        var errors = 0

        while (buf.hasRemaining()) {
            try {
                val typeOrdinal = buf.get().toInt() and 0xFF
                val tick = buf.int
                val type = McsrTimelineType.fromOrdinal(typeOrdinal)

                val event = parseTimeline(type, tick, buf)
                events.getOrPut(tick) { mutableListOf() }.add(event)
                count++
            } catch (e: Exception) {
                errors++
                if (errors > 100) {
                    println("[MCSR] Too many parse errors ($errors), aborting. Parsed $count events so far.")
                    break
                }
            }
        }

        println("[MCSR] Deserialized $count timeline events across ${events.size} ticks ($errors errors)")
        return events
    }

    /** Compute the maximum tick number in the timeline data. */
    fun maxTick(events: Map<Int, List<McsrTimelineEvent>>): Int =
        events.keys.maxOrNull() ?: 0

    private fun parseTimeline(type: McsrTimelineType, tick: Int, buf: ByteBuffer): McsrTimelineEvent {
        return when (type) {
            McsrTimelineType.PLAYER_POSITION -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val yaw = buf.short; val pitch = buf.short
                PlayerPositionEvent(tick, world, x, y, z, yaw, pitch)
            }
            McsrTimelineType.PLAYER_POSITION_LOOK -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val yaw = buf.short; val pitch = buf.short
                PlayerPositionLookEvent(tick, world, yaw, pitch)
            }
            McsrTimelineType.PLAYER_POSITION_POS -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                PlayerPositionPosEvent(tick, world, x, y, z)
            }
            McsrTimelineType.PLAYER_SPAWN -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val yaw = buf.short; val pitch = buf.short
                PlayerSpawnEvent(tick, world, x, y, z, yaw, pitch)
            }
            McsrTimelineType.PLAYER_POSE -> {
                val pose = buf.get()
                PlayerPoseEvent(tick, pose)
            }
            McsrTimelineType.PLAYER_REMOVE -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val death = buf.get().toInt() != 0
                PlayerRemoveEvent(tick, world, death)
            }
            McsrTimelineType.PLAYER_RIDE -> {
                val riding = buf.get().toInt() != 0
                PlayerRideEvent(tick, riding)
            }
            McsrTimelineType.PLAYER_PAUSE -> {
                val paused = buf.get().toInt() != 0
                PlayerPauseEvent(tick, paused)
            }
            McsrTimelineType.BLOCK_UPDATE_V1 -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long; val rawBlock = buf.int; val flags = buf.get()
                BlockUpdateV1Event(tick, world, pos, rawBlock, flags)
            }
            McsrTimelineType.BLOCK_UPDATE_V2 -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long; val rawBlock = buf.int; val flags = buf.get()
                BlockUpdateV2Event(tick, world, pos, rawBlock, flags)
            }
            McsrTimelineType.BLOCK_BREAK -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long
                BlockBreakEvent(tick, world, pos)
            }
            McsrTimelineType.BLOCK_REMOVE -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long
                BlockRemoveEvent(tick, world, pos)
            }
            McsrTimelineType.BLOCK_PROGRESS -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long; val progress = buf.get()
                BlockProgressEvent(tick, world, pos, progress)
            }
            McsrTimelineType.ENTITY_ADD -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val yaw = buf.short; val pitch = buf.short
                val entityId = buf.int; val entityTypeId = buf.int; val customData = buf.int
                EntityAddEvent(tick, world, x, y, z, yaw, pitch, entityId, entityTypeId, customData)
            }
            McsrTimelineType.ENTITY_REMOVE -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val entityId = buf.int
                EntityRemoveEvent(tick, world, entityId)
            }
            McsrTimelineType.ENTITY_MOVE -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val yaw = buf.short; val pitch = buf.short
                val entityId = buf.int
                EntityMoveEvent(tick, world, x, y, z, yaw, pitch, entityId)
            }
            McsrTimelineType.ENTITY_MOVE_LOOK -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val yaw = buf.short; val pitch = buf.short
                val entityId = buf.int
                EntityMoveLookEvent(tick, world, yaw, pitch, entityId)
            }
            McsrTimelineType.ENTITY_MOVE_POS -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val entityId = buf.int
                EntityMovePosEvent(tick, world, x, y, z, entityId)
            }
            McsrTimelineType.ENTITY_DAMAGE -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val entityId = buf.int
                EntityDamageEvent(tick, world, entityId)
            }
            McsrTimelineType.PROJECTILE_ENTITY -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val yaw = buf.short; val pitch = buf.short
                val entityId = buf.int; val entityTypeId = buf.int
                ProjectileEntityEvent(tick, world, x, y, z, yaw, pitch, entityId, entityTypeId)
            }
            McsrTimelineType.ITEM_EQUIP -> {
                val slot = buf.get()
                val itemId = buf.int
                ItemEquipEvent(tick, slot, itemId)
            }
            McsrTimelineType.ITEM_ENTITY -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val entityId = buf.int; val itemId = buf.int
                ItemEntityEvent(tick, world, x, y, z, entityId, itemId)
            }
            McsrTimelineType.DRAGON_HEALTH_UPDATE -> {
                val health = buf.float
                DragonHealthUpdateEvent(tick, health)
            }
            McsrTimelineType.DRAGON_CRYSTAL_UPDATE -> {
                val index = buf.get()
                DragonCrystalUpdateEvent(tick, index)
            }
            McsrTimelineType.DRAGON_MOVE -> {
                val x = buf.float; val y = buf.float; val z = buf.float
                DragonMoveEvent(tick, x, y, z)
            }
            McsrTimelineType.DRAGON_DEATH -> DragonDeathEvent(tick)
            McsrTimelineType.EXPLOSION_EFFECT -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val x = buf.float; val y = buf.float; val z = buf.float
                val radius = buf.float
                ExplosionEffectEvent(tick, world, x, y, z, radius)
            }
            McsrTimelineType.CHEST_ANIMATION -> {
                val world = McsrWorldType.fromOrdinal(buf.get().toInt() and 0xFF)
                val pos = buf.long; val open = buf.get().toInt() != 0
                ChestAnimationEvent(tick, world, pos, open)
            }
            McsrTimelineType.EMPTY,
            McsrTimelineType.DEBUG_DRAGON_REFRESH,
            McsrTimelineType.DEBUG_PLAYER_FOLLOW,
            McsrTimelineType.DEBUG_ENTITIES_REFRESH -> {
                // Debug/empty types have no data payload
                UnknownTimelineEvent(type, tick)
            }
        }
    }

    /**
     * MCSR rotation encoding: short = (angle / 0.01), wrapping via MathHelper.wrapDegrees.
     * To convert back: angle = short * 0.01, then wrapDegrees.
     */
    fun decodeRotation(encoded: Short): Float {
        var deg = encoded.toFloat() * 0.01f
        // Wrap to [-180, 180)
        deg %= 360f
        if (deg >= 180f) deg -= 360f
        if (deg < -180f) deg += 360f
        return deg
    }
}
