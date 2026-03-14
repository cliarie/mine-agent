package dev.replaycraft.mcap.ml

import dev.replaycraft.mcap.analytics.AnalyticsEmitter
import dev.replaycraft.mcap.analytics.RunOutcome
import dev.replaycraft.mcap.analytics.RunTracker
import dev.replaycraft.mcap.auth.IodineAuthClient
import dev.replaycraft.mcap.capture.RecordingEventHandler
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import java.io.File
import java.util.UUID

/**
 * Orchestrates the ML data export pipeline.
 *
 * Manages the lifecycle of gamestate.bin and gamestate_events.bin writers,
 * manifest.json, and S3 upload. Runs in parallel with the existing
 * packets.bin capture without modifying it.
 *
 * Authentication is handled by [IodineAuthClient] which performs a Minecraft
 * session handshake with the iodine server and returns a JWT used for all
 * server communication (analytics emission and file upload).
 */
object MlSessionManager {

    @Volatile
    private var active = false

    private var sessionId: String = ""
    private var sessionDir: File? = null
    private var gameStateWriter: GameStateWriter? = null
    private var gameStateEventWriter: GameStateEventWriter? = null
    private var tickCounter = 0
    private var wasSleeping = false
    private var wasAlive = true

    // --- Equipment tracking ---
    private val trackedEquipment = Array<ItemStack>(EquipmentSlot.values().size) { ItemStack.EMPTY }

    // --- Dimension tracking ---
    private var lastDimension: String? = null

    // --- Dragon health tracking ---
    private var lastDragonHealthPercent = -1f

    // --- Analytics ---
    private var runTracker: RunTracker? = null
    private var endedByDeath = false

    /**
     * Attempt to start an ML capture session.
     * Called when the player joins a world.
     * Returns true if ML capture was started, false if gate check failed.
     */
    fun tryStart(client: MinecraftClient): Boolean {
        if (active) return true

        when (SessionGate.checkGate(client)) {
            SessionGate.GateResult.DENY_PERMANENT -> {
                println("[MCAP ML] Session gate rejected — not in survival mode, skipping ML capture")
                return true // Return true to stop retrying (permanent rejection)
            }
            SessionGate.GateResult.DENY_TRANSIENT -> {
                return false // Return false to retry next tick (e.g. screen still open)
            }
            SessionGate.GateResult.ALLOW -> { /* proceed */ }
        }

        sessionId = UUID.randomUUID().toString()
        val baseDir = File(client.runDirectory, "mcap_replay/ml_sessions/$sessionId")
        baseDir.mkdirs()
        sessionDir = baseDir

        // Write manifest with in_progress status
        MlManifest.writeStart(baseDir, sessionId, client)

        // Open writers
        gameStateWriter = GameStateWriter(File(baseDir, "gamestate.bin")).also { it.open() }
        gameStateEventWriter = GameStateEventWriter(File(baseDir, "gamestate_events.bin")).also { it.open() }

        // Initialize analytics tracking
        val playerId = client.session.uuidOrNull?.toString() ?: "unknown"
        val modVersion = "0.1.0" // matches gradle.properties mod_version
        val tracker = RunTracker(sessionId, playerId, modVersion)
        runTracker = tracker
        RecordingEventHandler.runTracker = tracker

        // Try to load a cached iodine JWT from disk (fast, no I/O to server).
        // Then kick off a background auth so the token is ready by session end.
        IodineAuthClient.tryLoadCached(client)
        Thread {
            try {
                IodineAuthClient.authenticate(client)
            } catch (e: Exception) {
                println("[Iodine Auth] Background auth failed: ${e.message}")
            }
        }.apply {
            name = "Iodine-Auth-$sessionId"
            isDaemon = true
            start()
        }

        tickCounter = 0
        wasSleeping = false
        wasAlive = true
        endedByDeath = false
        trackedEquipment.fill(ItemStack.EMPTY)
        lastDimension = null
        lastDragonHealthPercent = -1f
        active = true

        println("[MCAP ML] ML capture started — session $sessionId")
        return true
    }

    /**
     * Called every tick from END_CLIENT_TICK to write game state.
     */
    fun onTick(client: MinecraftClient) {
        if (!active) return

        val player = client.player ?: return
        val world = client.world

        // Write fixed-size game state record
        gameStateWriter?.writeTick(client, player, tickCounter)

        // Check for inventory changes
        gameStateEventWriter?.checkInventory(player, tickCounter)

        // Detect player death
        val isAlive = player.isAlive
        if (wasAlive && !isAlive) {
            gameStateEventWriter?.writePlayerDied(tickCounter)
            endedByDeath = true
        }
        wasAlive = isAlive

        // Detect sleep
        val isSleeping = player.isSleeping
        if (!wasSleeping && isSleeping) {
            gameStateEventWriter?.writeSlept(tickCounter)
        }
        wasSleeping = isSleeping

        // Detect dimension changes
        val dimKey = world?.registryKey?.value?.toString()
        if (dimKey != null && dimKey != lastDimension) {
            if (lastDimension != null) {
                gameStateEventWriter?.writeDimensionChange(tickCounter, dimKey)
            }
            lastDimension = dimKey
        }

        // Track equipment changes (armor + held items)
        for (slot in EquipmentSlot.values()) {
            val current = player.getEquippedStack(slot)
            val idx = slot.ordinal
            if (!ItemStack.areEqual(trackedEquipment[idx], current)) {
                trackedEquipment[idx] = if (current.isEmpty) ItemStack.EMPTY else current.copy()
                val itemName = if (current.isEmpty) "empty" else Registries.ITEM.getId(current.item).path
                val enchanted = current.hasEnchantments()
                gameStateEventWriter?.writeEquipmentChange(tickCounter, slot.name.lowercase(), itemName, enchanted)
            }
        }

        // Track ender dragon health in the End
        if (world != null) {
            // Use boss bar to track dragon health (server sends BossBarS2CPacket)
            try {
                val bossBarHud = client.inGameHud?.bossBarHud
                if (bossBarHud != null) {
                    val barsField = bossBarHud.javaClass.getDeclaredField("bossBars")
                    barsField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val bars = barsField.get(bossBarHud) as? Map<*, *>
                    if (bars != null) {
                        for ((_, bar) in bars) {
                            if (bar is net.minecraft.entity.boss.BossBar) {
                                val name = bar.name?.string ?: ""
                                if (name.contains("Ender Dragon", ignoreCase = true)) {
                                    val healthPct = bar.percent
                                    if (healthPct != lastDragonHealthPercent) {
                                        gameStateEventWriter?.writeDragonHealth(tickCounter, healthPct)
                                        lastDragonHealthPercent = healthPct
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Reflection may fail — skip dragon health tracking
            }
        }

        tickCounter++
    }

    /**
     * Stop ML capture and finalize the session.
     * Called when the player leaves a world or the client is stopping.
     */
    fun stop(client: MinecraftClient) {
        if (!active) return
        active = false

        println("[MCAP ML] Stopping ML capture — session $sessionId ($tickCounter ticks)")

        // Use the cached iodine JWT (background auth in tryStart should have completed
        // by now). If the token is not yet available, authenticate() returns instantly
        // from the cache or returns null without blocking if the cache is empty.
        val token = IodineAuthClient.getCachedToken()

        if (token == null) {
            println("[MCAP ML] No iodine JWT available — analytics and upload will be skipped")
        }

        // Build summary before clearing state
        val tracker = runTracker
        val summary = if (tracker != null) {
            val dragonKilled = tracker.killTick >= 0
            val outcome = when {
                dragonKilled -> RunOutcome.WIN
                endedByDeath -> RunOutcome.DEATH
                else -> RunOutcome.QUIT
            }
            tracker.buildSummary(outcome)
        } else null

        // Clear analytics state
        RecordingEventHandler.runTracker = null
        runTracker = null

        // Close writers
        gameStateWriter?.close()
        gameStateWriter = null
        gameStateEventWriter?.close()
        gameStateEventWriter = null

        // Update manifest to complete
        val dir = sessionDir ?: return
        MlManifest.writeComplete(dir, sessionId, client)

        // Fire analytics and upload on background threads (non-blocking)
        if (token != null && summary != null) {
            AnalyticsEmitter(token).emit(summary)
        }
        if (token != null) {
            S3Uploader.uploadViaAuth(dir, sessionId, client.runDirectory, token)
        }

        sessionDir = null
    }

    /**
     * Whether an ML capture session is currently active.
     */
    fun isActive(): Boolean = active

    fun getSessionId(): String = sessionId

    fun getTickCounter(): Int = tickCounter

    /**
     * Write a CRAFTED event to the event stream.
     */
    fun onCrafted(itemName: String, count: Int) {
        if (!active) return
        gameStateEventWriter?.writeCrafted(tickCounter, itemName, count)
    }

    /**
     * Write an ADVANCEMENT event to the event stream.
     */
    fun onAdvancement(advancementId: String) {
        if (!active) return
        gameStateEventWriter?.writeAdvancement(tickCounter, advancementId)
    }
}
