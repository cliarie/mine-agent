package dev.replaycraft.mcap.ml

import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import java.io.File

/**
 * Manages the manifest.json for ML sessions.
 * Adds ML-specific fields alongside existing version metadata.
 */
object MlManifest {

    /**
     * Write manifest.json with status "in_progress" at session start.
     */
    fun writeStart(
        sessionDir: File,
        sessionId: String,
        client: MinecraftClient
    ) {
        client.player ?: return
        val world = client.world ?: return

        val playerUuid = client.session.uuidOrNull?.toString() ?: "unknown"
        // ClientWorld doesn't expose seed directly; use getLevelProperties if available
        val seed = getSeedSafe(world)
        val gameMode = "SURVIVAL"

        val json = buildManifestJson(
            sessionId = sessionId,
            playerUuid = playerUuid,
            seed = seed,
            gameMode = gameMode,
            status = "in_progress"
        )

        val manifestFile = File(sessionDir, "manifest.json")
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(json)
    }

    /**
     * Overwrite manifest.json with status "complete" at session end.
     */
    fun writeComplete(
        sessionDir: File,
        sessionId: String,
        client: MinecraftClient
    ) {
        val playerUuid = client.session.uuidOrNull?.toString() ?: "unknown"
        // World may be null at this point if disconnecting
        val seed = client.world?.let { getSeedSafe(it) } ?: 0L
        val gameMode = "SURVIVAL"

        val json = buildManifestJson(
            sessionId = sessionId,
            playerUuid = playerUuid,
            seed = seed,
            gameMode = gameMode,
            status = "complete"
        )

        val manifestFile = File(sessionDir, "manifest.json")
        manifestFile.writeText(json)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getSeedSafe(world: ClientWorld): Long {
        // ClientWorld doesn't expose seed on the client side.
        // The server sends it via GameJoinS2CPacket but ClientWorld.Properties
        // doesn't store it in a public accessor. Return 0 as safe fallback.
        return 0L
    }

    private fun buildManifestJson(
        sessionId: String,
        playerUuid: String,
        seed: Long,
        gameMode: String,
        status: String
    ): String {
        // Build JSON manually to avoid adding a JSON library dependency
        return """
            |{
            |  "schema_version": 1,
            |  "mc_version": "1.20.1",
            |  "yarn": "1.20.1+build.10",
            |  "session_id": "$sessionId",
            |  "player_uuid": "$playerUuid",
            |  "seed": $seed,
            |  "game_mode": "$gameMode",
            |  "status": "$status"
            |}
        """.trimMargin()
    }
}
