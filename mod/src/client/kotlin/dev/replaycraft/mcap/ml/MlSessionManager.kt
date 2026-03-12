package dev.replaycraft.mcap.ml

import dev.replaycraft.mcap.analytics.AnalyticsEmitter
import dev.replaycraft.mcap.analytics.RunOutcome
import dev.replaycraft.mcap.analytics.RunTracker
import dev.replaycraft.mcap.capture.RecordingEventHandler
import net.minecraft.client.MinecraftClient
import java.io.File
import java.util.UUID

/**
 * Orchestrates the ML data export pipeline.
 *
 * Manages the lifecycle of gamestate.bin and gamestate_events.bin writers,
 * manifest.json, and S3 upload. Runs in parallel with the existing
 * packets.bin capture without modifying it.
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

    // --- Analytics ---
    private var runTracker: RunTracker? = null
    private var analyticsEmitter: AnalyticsEmitter? = null
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

        // Load analytics config
        val config = loadAnalyticsConfig(client.runDirectory)
        val endpoint = config.getProperty("analytics.endpoint", "")
        val apiKey = config.getProperty("analytics.api_key", "")
        val enabled = config.getProperty("analytics.enabled", "true")
        if (enabled == "true" && apiKey.isNotBlank()) {
            analyticsEmitter = AnalyticsEmitter(endpoint, apiKey)
        } else {
            analyticsEmitter = null
            println("[MCAP Analytics] Analytics disabled — set analytics.api_key to enable")
        }

        tickCounter = 0
        wasSleeping = false
        wasAlive = true
        endedByDeath = false
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

        // Emit analytics before finalizing (lightweight, fires first)
        val tracker = runTracker
        val emitter = analyticsEmitter
        if (tracker != null && emitter != null) {
            val dragonKilled = tracker.killTick >= 0
            val outcome = when {
                dragonKilled -> RunOutcome.WIN
                endedByDeath -> RunOutcome.DEATH
                else -> RunOutcome.QUIT
            }
            val summary = tracker.buildSummary(outcome)
            emitter.emit(summary)
        }

        // Clear analytics state
        RecordingEventHandler.runTracker = null
        runTracker = null
        analyticsEmitter = null

        // Close writers
        gameStateWriter?.close()
        gameStateWriter = null
        gameStateEventWriter?.close()
        gameStateEventWriter = null

        // Update manifest to complete
        val dir = sessionDir ?: return
        MlManifest.writeComplete(dir, sessionId, client)

        // Upload session files to S3 via mine-auth presigned URLs (fire-and-forget)
        S3Uploader.uploadViaAuth(dir, sessionId, client.runDirectory, client)

        sessionDir = null
    }

    /**
     * Whether an ML capture session is currently active.
     */
    fun isActive(): Boolean = active

    fun getSessionId(): String = sessionId

    /**
     * Load analytics config from mcap_ml_config.properties.
     */
    private fun loadAnalyticsConfig(runDir: File): java.util.Properties {
        val props = java.util.Properties()
        val configFile = File(runDir, "mcap_ml_config.properties")
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                println("[MCAP Analytics] Failed to load config: ${e.message}")
            }
        }
        return props
    }
}
