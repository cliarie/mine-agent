package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.world.LightType
import net.minecraft.world.World
import net.minecraft.world.biome.Biome

/**
 * Reads live game state each tick and stores it in a structured TickRecord.
 *
 * This runs at END_CLIENT_TICK (after all packet processing is complete),
 * so entity positions are final and inventory is settled. Follows the user's
 * architecture: "read from Minecraft's already-decoded game state, not from
 * raw bytes."
 */
object GameStateCapture {

    /**
     * Capture a single tick's worth of game state from the live Minecraft client.
     * Returns null if the player/world is not available.
     */
    fun captureTick(client: MinecraftClient, tickNumber: Int): TickRecord? {
        val player = client.player ?: return null
        val world = client.world ?: return null

        val pos = player.pos
        val blockPos = player.blockPos

        // Input bitmask (Int32) — compact and fast to decode on Python side
        val keyboardMask = buildKeyboardMask(client, player)

        // Mouse buttons via GLFW
        val mouseButtons = captureMouseButtons(client)

        // Biome at player position
        val biomeId = getBiomeId(world, blockPos)

        // Light levels
        val blockLight = world.getLightLevel(LightType.BLOCK, blockPos)
        val skyLight = world.getLightLevel(LightType.SKY, blockPos)

        // Weather
        val isRaining = world.isRaining
        val isThundering = world.isThundering

        // Time
        val timeOfDay = world.timeOfDay
        val dayTime = world.time

        return TickRecord(
            tick = tickNumber,
            timestampMs = System.currentTimeMillis(),
            // Player position
            x = pos.x,
            y = pos.y,
            z = pos.z,
            // Player rotation
            yaw = player.yaw,
            pitch = player.pitch,
            headYaw = player.headYaw,
            // Player state
            health = player.health,
            hunger = player.hungerManager.foodLevel,
            saturation = player.hungerManager.saturationLevel,
            experienceLevel = player.experienceLevel,
            experienceProgress = player.experienceProgress,
            isOnGround = player.isOnGround,
            isSprinting = player.isSprinting,
            isSneaking = player.isSneaking,
            isSwimming = player.isSwimming,
            isTouchingWater = player.isTouchingWater,
            // Velocity
            velX = player.velocity.x,
            velY = player.velocity.y,
            velZ = player.velocity.z,
            // Input
            keyboardMask = keyboardMask,
            mouseButtons = mouseButtons,
            hotbarSlot = player.inventory.selectedSlot,
            // World state
            biomeId = biomeId,
            blockLight = blockLight,
            skyLight = skyLight,
            isRaining = isRaining,
            isThundering = isThundering,
            timeOfDay = timeOfDay,
            dayTime = dayTime,
            // Screen
            screenType = classifyScreen(client),
        )
    }

    /**
     * Build Int32 keyboard bitmask from current input state.
     * Bit layout matches user spec.
     */
    private fun buildKeyboardMask(client: MinecraftClient, player: PlayerEntity): Int {
        val opts = client.options
        var mask = 0
        if (opts.forwardKey.isPressed) mask = mask or (1 shl 0)
        if (opts.backKey.isPressed) mask = mask or (1 shl 1)
        if (opts.leftKey.isPressed) mask = mask or (1 shl 2)
        if (opts.rightKey.isPressed) mask = mask or (1 shl 3)
        if (opts.jumpKey.isPressed) mask = mask or (1 shl 4)
        if (opts.sneakKey.isPressed) mask = mask or (1 shl 5)
        if (opts.sprintKey.isPressed) mask = mask or (1 shl 6)
        if (opts.attackKey.isPressed) mask = mask or (1 shl 7)
        if (opts.useKey.isPressed) mask = mask or (1 shl 8)
        if (opts.pickItemKey.isPressed) mask = mask or (1 shl 9)
        if (opts.dropKey.isPressed) mask = mask or (1 shl 10)
        if (player.handSwinging) mask = mask or (1 shl 11)
        return mask
    }

    /**
     * Capture mouse button state via GLFW.
     */
    private fun captureMouseButtons(client: MinecraftClient): Int {
        var buttons = 0
        try {
            val window = client.window.handle
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                buttons = buttons or 1
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                buttons = buttons or 2
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                buttons = buttons or 4
        } catch (_: Exception) {}
        return buttons
    }

    /**
     * Get the biome identifier string at the given position.
     */
    private fun getBiomeId(world: World, pos: BlockPos): String {
        return try {
            val biomeEntry = world.getBiome(pos)
            val biomeKey = biomeEntry.key.orElse(null)
            biomeKey?.value?.toString() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Classify the current screen into a type string for ML labeling.
     */
    private fun classifyScreen(client: MinecraftClient): String {
        val screen = client.currentScreen ?: return "none"
        val name = screen.javaClass.simpleName
        return when {
            name.contains("Inventory") -> "inventory"
            name.contains("Chest") || name.contains("Generic") -> "chest"
            name.contains("Crafting") -> "crafting"
            name.contains("Furnace") || name.contains("Smoker") || name.contains("BlastFurnace") -> "furnace"
            name.contains("Enchant") -> "enchanting"
            name.contains("Anvil") -> "anvil"
            name.contains("Brewing") -> "brewing"
            name.contains("Beacon") -> "beacon"
            name.contains("Merchant") || name.contains("Villager") -> "trading"
            name.contains("Hopper") -> "hopper"
            name.contains("Shulker") -> "shulker_box"
            name.contains("Creative") -> "creative"
            else -> "other"
        }
    }
}

/**
 * A single tick's worth of game state, captured from live Minecraft.
 * This is the source of truth for the tick_stream Arrow IPC file.
 */
data class TickRecord(
    val tick: Int,
    val timestampMs: Long,
    // Player position (doubles for precision)
    val x: Double,
    val y: Double,
    val z: Double,
    // Player rotation
    val yaw: Float,
    val pitch: Float,
    val headYaw: Float,
    // Player state
    val health: Float,
    val hunger: Int,
    val saturation: Float,
    val experienceLevel: Int,
    val experienceProgress: Float,
    val isOnGround: Boolean,
    val isSprinting: Boolean,
    val isSneaking: Boolean,
    val isSwimming: Boolean,
    val isTouchingWater: Boolean,
    // Velocity
    val velX: Double,
    val velY: Double,
    val velZ: Double,
    // Input
    val keyboardMask: Int,
    val mouseButtons: Int,
    val hotbarSlot: Int,
    // World state
    val biomeId: String,
    val blockLight: Int,
    val skyLight: Int,
    val isRaining: Boolean,
    val isThundering: Boolean,
    val timeOfDay: Long,
    val dayTime: Long,
    // Screen
    val screenType: String,
)
