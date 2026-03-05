package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import java.io.File
import java.util.UUID

/**
 * Writes metadata.json for each capture session.
 *
 * Written at session start with status="in_progress", overwritten at session
 * end with status="complete". The Python pipeline skips sessions where
 * status != "complete" so crashed sessions don't corrupt training data.
 */
class SessionMetadata(private val outputFile: File) {

    var sessionId: String = UUID.randomUUID().toString()
        private set
    var startTick: Int = 0
        private set
    var startTimeMs: Long = 0
        private set

    // Fields captured at session start, preserved in writeEnd()
    private var playerUuid: String = "unknown"
    private var playerName: String = "unknown"
    private var seed: String = "null"
    private var gameMode: String = "UNKNOWN"
    private var dimension: String = "unknown"

    /**
     * Write initial metadata at session start.
     */
    fun writeStart(client: MinecraftClient, sessionId: String, startTick: Int) {
        this.sessionId = sessionId
        this.startTick = startTick
        this.startTimeMs = System.currentTimeMillis()

        val player = client.player
        val world = client.world

        // Capture fields that won't be available at session end
        playerUuid = player?.uuidAsString ?: "unknown"
        playerName = player?.gameProfile?.name ?: "unknown"
        seed = try { getSeed(client) } catch (_: Exception) { "null" }
        gameMode = try { getGameMode(client) } catch (_: Exception) { "UNKNOWN" }
        dimension = world?.registryKey?.value?.toString() ?: "unknown"

        writeJson(endTick = null, tickCount = 0, eventCount = 0, status = "in_progress")
    }

    /**
     * Overwrite metadata at session end with final counts and complete status.
     * Preserves all fields captured at session start.
     */
    fun writeEnd(endTick: Int, tickCount: Int, eventCount: Int) {
        writeJson(endTick = endTick, tickCount = tickCount, eventCount = eventCount, status = "complete")
    }

    private fun writeJson(endTick: Int?, tickCount: Int, eventCount: Int, status: String) {
        val endTimeMs = if (status == "complete") System.currentTimeMillis() else null

        val json = buildString {
            append("{\n")
            append("  \"session_id\": \"$sessionId\",\n")
            append("  \"player_uuid\": \"$playerUuid\",\n")
            append("  \"player_name\": \"$playerName\",\n")
            append("  \"start_tick\": $startTick,\n")
            append("  \"end_tick\": ${endTick ?: "null"},\n")
            append("  \"start_time_ms\": $startTimeMs,\n")
            append("  \"end_time_ms\": ${endTimeMs ?: "null"},\n")
            if (endTimeMs != null) {
                append("  \"duration_seconds\": ${(endTimeMs - startTimeMs) / 1000.0},\n")
            }
            append("  \"tick_count\": $tickCount,\n")
            append("  \"event_count\": $eventCount,\n")
            append("  \"mc_version\": \"1.20.1\",\n")
            append("  \"mod_version\": \"0.1.0\",\n")
            append("  \"seed\": $seed,\n")
            append("  \"game_mode\": \"$gameMode\",\n")
            append("  \"dimension\": \"$dimension\",\n")
            append("  \"status\": \"$status\"\n")
            append("}")
        }

        try {
            outputFile.writeText(json)
        } catch (e: Exception) {
            println("[MCAP-ML] Error writing session metadata: ${e.message}")
        }
    }

    private fun getSeed(client: MinecraftClient): String {
        return try {
            // In singleplayer, we can access the seed
            val server = client.server
            if (server != null) {
                server.overworld.seed.toString()
            } else {
                "null"
            }
        } catch (_: Exception) {
            "null"
        }
    }

    private fun getGameMode(client: MinecraftClient): String {
        return try {
            val interactionManager = client.interactionManager
            interactionManager?.currentGameMode?.name ?: "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }
}
