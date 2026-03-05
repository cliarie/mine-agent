package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import java.io.File
import java.util.UUID

/**
 * Orchestrates the ML data capture pipeline lifecycle.
 *
 * Manages session creation, per-tick data capture, periodic flushing,
 * and S3 upload on session end. This is the single entry point that
 * McapReplayClient calls into.
 *
 * Data flow per the user's architecture:
 *   END_CLIENT_TICK fires
 *     -> GameStateCapture reads live game state
 *     -> ArrowTickWriter appends to tick_stream.arrow
 *     -> EventCapture detects inventory deltas, emits to events.arrow
 *     -> SessionMetadata tracks session state
 *     -> On session end: flush all, upload to S3
 */
class MlPipeline(private val baseDir: String) {

    private var sessionId: String? = null
    private var sessionDir: File? = null
    private var tickWriter: ArrowTickWriter? = null
    private var eventCapture: EventCapture? = null
    private var metadata: SessionMetadata? = null
    private var tickNumber: Int = 0
    private var active = false

    private val s3Uploader = S3Uploader()
    private val uploadThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcap-ml-upload").also { it.isDaemon = true }
    }

    /**
     * Start a new capture session. Called when the player joins a world.
     */
    fun startSession(client: MinecraftClient) {
        if (active) {
            endSession()
        }

        sessionId = UUID.randomUUID().toString()
        val mlDataDir = File(baseDir, "ml_data")
        sessionDir = File(mlDataDir, "session_$sessionId")
        sessionDir!!.mkdirs()

        tickNumber = 0

        try {
            // Initialize writers
            tickWriter = ArrowTickWriter(File(sessionDir, "tick_stream.arrow"))
            tickWriter!!.open()

            eventCapture = EventCapture(File(sessionDir, "events.arrow"))
            eventCapture!!.open()

            metadata = SessionMetadata(File(sessionDir, "metadata.json"))
            metadata!!.writeStart(client, sessionId!!, 0)

            active = true
            println("[MCAP-ML] Started capture session $sessionId")
        } catch (e: Exception) {
            println("[MCAP-ML] Failed to start session: ${e.message}")
            e.printStackTrace()
            cleanup()
        }
    }

    /**
     * Called each tick (END_CLIENT_TICK) to capture game state.
     * This is the hot path — must be fast.
     */
    fun onTick(client: MinecraftClient) {
        if (!active) return

        val player = client.player ?: return

        try {
            // Capture tick-level game state
            val record = GameStateCapture.captureTick(client, tickNumber)
            if (record != null) {
                tickWriter?.addTick(record)
            }

            // Capture inventory deltas and other events
            eventCapture?.onTick(tickNumber, player)

            tickNumber++
        } catch (e: Exception) {
            // Don't let capture errors crash the game
            if (tickNumber % 1000 == 0) {
                println("[MCAP-ML] Tick capture error at tick $tickNumber: ${e.message}")
            }
        }
    }

    /**
     * Emit a discrete event (block break, death, screen open, etc.)
     * Called from event handlers/mixins.
     */
    fun emitEvent(
        eventType: String,
        blockX: Int? = null,
        blockY: Int? = null,
        blockZ: Int? = null,
        blockId: String? = null,
        detail: String? = null,
    ) {
        if (!active) return
        try {
            eventCapture?.emitEvent(tickNumber, eventType, blockX, blockY, blockZ, blockId, detail)
        } catch (_: Exception) {}
    }

    /**
     * End the current capture session. Called when the player leaves a world
     * or the client is stopping.
     *
     * Flushes all data, writes final metadata, then triggers async S3 upload.
     */
    fun endSession() {
        if (!active) return
        active = false

        val sid = sessionId ?: return
        val dir = sessionDir ?: return

        println("[MCAP-ML] Ending session $sid (${tickNumber} ticks)")

        try {
            // Close writers (flushes remaining data)
            val tickCount = tickWriter?.close() ?: 0
            val eventCount = eventCapture?.close() ?: 0

            // Write final metadata with complete status
            metadata?.writeEnd(tickNumber, tickCount, eventCount)

            println("[MCAP-ML] Session $sid complete: $tickCount ticks, $eventCount events")
            println("[MCAP-ML] Data written to ${dir.absolutePath}")

            // Async S3 upload (doesn't block the game thread)
            if (s3Uploader.isConfigured) {
                val uploadDir = dir
                val uploadSessionId = sid
                uploadThread.submit {
                    try {
                        val success = s3Uploader.uploadSession(uploadDir, uploadSessionId)
                        if (success) {
                            println("[MCAP-ML] Session $uploadSessionId uploaded to S3")
                        }
                    } catch (e: Exception) {
                        println("[MCAP-ML] S3 upload failed for $uploadSessionId: ${e.message}")
                    }
                }
            } else {
                println("[MCAP-ML] S3 not configured. Files remain at ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            println("[MCAP-ML] Error ending session: ${e.message}")
        } finally {
            cleanup()
        }
    }

    /**
     * Check if a capture session is currently active.
     */
    fun isActive(): Boolean = active

    /**
     * Get current tick number.
     */
    fun getCurrentTick(): Int = tickNumber

    /**
     * Get current session ID, or null if no session is active.
     */
    fun getSessionId(): String? = if (active) sessionId else null

    private fun cleanup() {
        tickWriter = null
        eventCapture = null
        metadata = null
        sessionId = null
        sessionDir = null
        tickNumber = 0
    }

    /**
     * Shut down the pipeline (called on client stop).
     */
    fun shutdown() {
        endSession()
        uploadThread.shutdown()
    }
}
