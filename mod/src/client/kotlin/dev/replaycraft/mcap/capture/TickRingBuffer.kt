package dev.replaycraft.mcap.capture

import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.GameOptions
import net.minecraft.entity.player.PlayerEntity
import org.lwjgl.glfw.GLFW
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class TickRingBuffer(val capacity: Int) {
    // Fixed-size record for JNI batching.
    // Enhanced layout (little-endian, 48 bytes):
    //
    // u16 flags       (0-1)    movement keys, jump, sneak, sprint, screen, swing, attack, use
    // u8  hotbar      (2)      selected slot 0-8 or 0xFF
    // u8  mouseBtn    (3)      bit0=left, bit1=right, bit2=middle
    // i16 yaw_fp      (4-5)    yaw * 100.0 as fixed-point
    // i16 pitch_fp    (6-7)    pitch * 100.0 as fixed-point
    // u32 tick        (8-11)
    // f32 x           (12-15)
    // f32 y           (16-19)
    // f32 z           (20-23)
    // f32 health      (24-27)  player health (0-20)
    // u8  food        (28)     food level (0-20)
    // u8  screenType  (29)     0=none, 1=inventory, 2=chest, 3=crafting, 4=furnace, etc.
    // u16 xpLevel     (30-31)  experience level
    // f32 velX        (32-35)  velocity X
    // f32 velY        (36-39)  velocity Y
    // f32 velZ        (40-43)  velocity Z
    // i16 cursorX     (44-45)  cursor screen X (scaled)
    // i16 cursorY     (46-47)  cursor screen Y (scaled)
    val recordSize: Int = 48

    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity * recordSize).order(ByteOrder.LITTLE_ENDIAN)

    private val writeIdx = AtomicInteger(0)
    private val readIdx = AtomicInteger(0)

    @Volatile var lastDrainedStartTick: Int = 0
        private set

    private var localTick: Int = 0

    fun tryWriteFromClient(client: MinecraftClient, player: PlayerEntity) {
        val w = writeIdx.get()
        val r = readIdx.get()
        if (w - r >= capacity) {
            return
        }

        val options: GameOptions = client.options

        // ---- flags (u16) ----
        var flags = 0
        if (options.forwardKey.isPressed) flags = flags or (1 shl 0)
        if (options.backKey.isPressed) flags = flags or (1 shl 1)
        if (options.leftKey.isPressed) flags = flags or (1 shl 2)
        if (options.rightKey.isPressed) flags = flags or (1 shl 3)
        if (options.jumpKey.isPressed) flags = flags or (1 shl 4)
        if (options.sneakKey.isPressed) flags = flags or (1 shl 5)
        if (options.sprintKey.isPressed) flags = flags or (1 shl 6)
        // Bit 7: screen is open (inventory, chest, etc.)
        if (client.currentScreen != null) flags = flags or (1 shl 7)
        // Bit 8: player is swinging arm
        if (player.handSwinging) flags = flags or (1 shl 8)
        // Bit 9: attack key pressed
        if (options.attackKey.isPressed) flags = flags or (1 shl 9)
        // Bit 10: use key pressed
        if (options.useKey.isPressed) flags = flags or (1 shl 10)
        // Bit 11: player on ground
        if (player.isOnGround) flags = flags or (1 shl 11)
        // Bit 12: player in water
        if (player.isTouchingWater) flags = flags or (1 shl 12)
        // Bit 13: player swimming (full swim pose)
        if (player.isSwimming) flags = flags or (1 shl 13)
        // Bit 14: player crawling (swimming pose without water — 1-block gap)
        if (player.isInSwimmingPose && !player.isSwimming) flags = flags or (1 shl 14)
        // Bit 15: player elytra gliding
        if (player.isFallFlying) flags = flags or (1 shl 15)

        val hotbar = player.inventory.selectedSlot

        // ---- mouse buttons (u8) ----
        var mouseBtn = 0
        try {
            val windowHandle = client.window.handle
            if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) mouseBtn = mouseBtn or 1
            if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) mouseBtn = mouseBtn or 2
            if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS) mouseBtn = mouseBtn or 4
        } catch (_: Exception) {
            // Ignore GLFW errors
        }

        val yawFp = (player.yaw * 100.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        val pitchFp = (player.pitch * 100.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        // ---- screen type (u8) ----
        val screenType: Byte = classifyScreen(client)

        // ---- cursor position ----
        var cursorX: Short = 0
        var cursorY: Short = 0
        try {
            val mx = client.mouse.x
            val my = client.mouse.y
            val scale = client.window.scaleFactor
            cursorX = (mx / scale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            cursorY = (my / scale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        } catch (_: Exception) {}

        val slot = (w % capacity) * recordSize
        buf.putShort(slot + 0, flags.toShort())
        buf.put(slot + 2, if (hotbar in 0..8) hotbar.toByte() else 0xFF.toByte())
        buf.put(slot + 3, mouseBtn.toByte())
        buf.putShort(slot + 4, yawFp)
        buf.putShort(slot + 6, pitchFp)
        buf.putInt(slot + 8, localTick)
        buf.putFloat(slot + 12, player.x.toFloat())
        buf.putFloat(slot + 16, player.y.toFloat())
        buf.putFloat(slot + 20, player.z.toFloat())
        buf.putFloat(slot + 24, player.health)
        buf.put(slot + 28, player.hungerManager.foodLevel.toByte())
        buf.put(slot + 29, screenType)
        buf.putShort(slot + 30, player.experienceLevel.toShort())
        buf.putFloat(slot + 32, player.velocity.x.toFloat())
        buf.putFloat(slot + 36, player.velocity.y.toFloat())
        buf.putFloat(slot + 40, player.velocity.z.toFloat())
        buf.putShort(slot + 44, cursorX)
        buf.putShort(slot + 46, cursorY)

        localTick++
        writeIdx.lazySet(w + 1)
    }

    fun drainToByteArray(out: ByteArray): Int {
        val r = readIdx.get()
        val w = writeIdx.get()
        val available = w - r
        if (available <= 0) return 0

        val maxRecords = out.size / recordSize
        val toRead = minOf(available, maxRecords)
        if (toRead <= 0) return 0

        lastDrainedStartTick = buf.getInt(((r % capacity) * recordSize) + 8)

        var outOff = 0
        for (i in 0 until toRead) {
            val idx = (r + i) % capacity
            val base = idx * recordSize
            for (j in 0 until recordSize) {
                out[outOff + j] = buf.get(base + j)
            }
            outOff += recordSize
        }

        readIdx.lazySet(r + toRead)
        return outOff
    }

    companion object {
        /** Classify the current screen into a type ID for the tick record. */
        fun classifyScreen(client: MinecraftClient): Byte {
            val screen = client.currentScreen ?: return 0
            val name = screen.javaClass.simpleName
            return when {
                name.contains("Inventory") -> 1
                name.contains("Chest") || name.contains("Generic") -> 2
                name.contains("Crafting") -> 3
                name.contains("Furnace") || name.contains("Smoker") || name.contains("BlastFurnace") -> 4
                name.contains("Enchant") -> 5
                name.contains("Anvil") -> 6
                name.contains("Brewing") -> 7
                name.contains("Beacon") -> 8
                name.contains("Merchant") || name.contains("Villager") -> 9
                name.contains("Hopper") -> 10
                name.contains("Shulker") -> 11
                name.contains("Creative") -> 12
                else -> 0xFF.toByte() // unknown screen
            }
        }
    }
}
