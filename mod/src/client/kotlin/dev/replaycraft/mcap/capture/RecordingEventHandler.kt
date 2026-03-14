package dev.replaycraft.mcap.capture

import dev.replaycraft.mcap.analytics.RunTracker
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.registry.Registries

/**
 * Handles analytics event forwarding for the ML pipeline.
 *
 * Routes game events (dimension changes, deaths, inventory deltas,
 * entity kills, block placement, advancements) to the active [RunTracker].
 *
 * Mixins call into this singleton; MlSessionManager owns the RunTracker lifecycle.
 */
object RecordingEventHandler {

    // --- Analytics tracking ---
    var runTracker: RunTracker? = null
    private var lastDimension: String? = null
    private var analyticsInventory: MutableMap<String, Int> = mutableMapOf()
    private var analyticsWasAlive = true

    fun reset() {
        lastDimension = null
        analyticsInventory.clear()
        analyticsWasAlive = true
    }

    /**
     * Called each world tick to forward analytics events to RunTracker.
     */
    fun onPlayerTick() {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        val tracker = runTracker ?: return

        tracker.onTick()

        // Dimension change detection
        val dimKey = client.world?.registryKey?.value?.toString()
        if (dimKey != null && dimKey != lastDimension) {
            if (lastDimension != null) {
                tracker.onDimensionChange(dimKey)
            }
            lastDimension = dimKey
        }

        // Death detection
        val isAlive = player.isAlive
        if (analyticsWasAlive && !isAlive) {
            tracker.onPlayerDeath()
        }
        analyticsWasAlive = isAlive

        // Aggregated inventory delta tracking
        trackAnalyticsInventory(player, tracker)
    }

    /**
     * Aggregate inventory tracking for analytics.
     * Computes per-item count deltas (same approach as GameStateEventWriter.checkInventory).
     */
    private fun trackAnalyticsInventory(player: PlayerEntity, tracker: RunTracker) {
        try {
            val inventory = player.inventory
            val currentInventory = mutableMapOf<String, Int>()
            for (i in 0 until inventory.size()) {
                val stack = inventory.getStack(i)
                if (!stack.isEmpty) {
                    val itemPath = "minecraft:" + Registries.ITEM.getId(stack.item).path
                    currentInventory[itemPath] = (currentInventory[itemPath] ?: 0) + stack.count
                }
            }

            val allKeys = (analyticsInventory.keys + currentInventory.keys).toSet()
            for (key in allKeys) {
                val prev = analyticsInventory[key] ?: 0
                val curr = currentInventory[key] ?: 0
                if (prev != curr) {
                    tracker.onInventoryDelta(key, curr - prev)
                }
            }

            analyticsInventory = currentInventory
        } catch (_: Exception) {
            // Ignore inventory tracking errors
        }
    }

    /**
     * Called when an entity status packet is received.
     * Status 3 = entity death — used to detect dragon kill.
     */
    fun onEntityStatus(entityId: Int, status: Int) {
        if (status != 3) return
        val tracker = runTracker ?: return
        val client = MinecraftClient.getInstance()
        val entity = client.world?.getEntityById(entityId) ?: return
        val typeId = Registries.ENTITY_TYPE.getId(entity.type).toString()
        tracker.onEntityKilled(typeId)
    }

    /**
     * Called when the player places a block.
     * Used to track bed placement in the End.
     */
    fun onBlockPlaced(blockId: String) {
        runTracker?.onBlockPlaced(blockId)
    }

    /**
     * Called from AdvancementMixin when a speedrun-relevant advancement is granted.
     * Routes to RunTracker for split milestone detection.
     */
    fun onAdvancement(advancementId: String) {
        runTracker?.onAdvancement(advancementId)
    }
}
