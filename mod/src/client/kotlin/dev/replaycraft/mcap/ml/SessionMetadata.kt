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

    /**
     * Write initial metadata at session start.
     */
    fun writeStart(client: MinecraftClient, sessionId: String, startTick: Int) {
        this.sessionId = sessionId
        this.startTick = startTick
        this.startTimeMs = System.currentTimeMillis()

        val player = client.player
        val world = client.world

        val json = buildString {
            append("{\n")
            append("  \"session_id\": \"$sessionId\",\n")
            append("  \"player_uuid\": \"${player?.uuidAsString ?: "unknown"}\",\n")
            append("  \"player_name\": \"${player?.gameProfile?.name ?: "unknown"}\",\n")
            append("  \"start_tick\": $startTick,\n")
            append("  \"end_tick\": null,\n")
            append("  \"start_time_ms\": $startTimeMs,\n")
            append("  \"end_time_ms\": null,\n")
            append("  \"tick_count\": 0,\n")
            append("  \"event_count\": 0,\n")
            append("  \"mc_version\": \"1.20.1\",\n")
            append("  \"mod_version\": \"0.1.0\",\n")
            val seed = try { world?.let { getSeed(it) } ?: "null" } catch (_: Exception) { "null" }
            append("  \"seed\": $seed,\n")
            append("  \"game_mode\": \"${player?.let { getGameMode(client) } ?: "UNKNOWN"}\",\n")
            append("  \"dimension\": \"${world?.registryKey?.value?.toString() ?: "unknown"}\",\n")
            append("  \"status\": \"in_progress\"\n")
            append("}")
        }

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(json)
    }

    /**
     * Overwrite metadata at session end with final counts and complete status.
     */
    fun writeEnd(endTick: Int, tickCount: Int, eventCount: Int) {
        val endTimeMs = System.currentTimeMillis()

        val json = buildString {
            append("{\n")
            append("  \"session_id\": \"$sessionId\",\n")
            // Preserve fields from start
            append("  \"start_tick\": $startTick,\n")
            append("  \"end_tick\": $endTick,\n")
            append("  \"start_time_ms\": $startTimeMs,\n")
            append("  \"end_time_ms\": $endTimeMs,\n")
            append("  \"duration_seconds\": ${(endTimeMs - startTimeMs) / 1000.0},\n")
            append("  \"tick_count\": $tickCount,\n")
            append("  \"event_count\": $eventCount,\n")
            append("  \"mc_version\": \"1.20.1\",\n")
            append("  \"mod_version\": \"0.1.0\",\n")
            append("  \"status\": \"complete\"\n")
            append("}")
        }

        try {
            outputFile.writeText(json)
        } catch (e: Exception) {
            println("[MCAP-ML] Error writing session metadata: ${e.message}")
        }
    }

    private fun getSeed(world: net.minecraft.world.World): String {
        return try {
            // In singleplayer, we can access the seed
            val client = MinecraftClient.getInstance()
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
