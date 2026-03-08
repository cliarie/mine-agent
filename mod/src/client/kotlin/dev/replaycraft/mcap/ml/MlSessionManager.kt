package dev.replaycraft.mcap.ml

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

        tickCounter = 0
        wasSleeping = false
        wasAlive = true
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
}
