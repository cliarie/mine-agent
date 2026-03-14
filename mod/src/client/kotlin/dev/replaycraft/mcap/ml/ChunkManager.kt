package dev.replaycraft.mcap.ml

import dev.replaycraft.mcap.auth.IodineAuthClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import java.io.File

/**
 * Manages rolling 5-minute chunk uploads for ML session data.
 *
 * Every [CHUNK_SIZE_TICKS] ticks (~5 minutes at 20 tps), the current chunk is
 * closed, converted to parquet, and uploaded in the background while a new chunk
 * starts writing immediately. Session end flushes and uploads the final partial chunk.
 *
 * Each chunk gets its own subdirectory under `chunks/` with the same filenames
 * that [GameStateWriter] and [GameStateEventWriter] always produce, so
 * `convert_upload.py` works unchanged — just pointed at the chunk directory.
 *
 * Thread safety: writers are only touched from the game thread. Background upload
 * threads only read already-closed files in independent directories.
 */
class ChunkManager(
    private val sessionDir: File,
    private val sessionId: String,
    private val playerId: String,
    private val runDir: File,
    private val jwtToken: String
) {
    companion object {
        const val CHUNK_SIZE_TICKS = 6000   // 5 minutes at 20 tps
    }

    private var currentChunkIndex = 0
    private var currentChunkStartTick = 0
    private var currentChunkStartTimeMs = 0L
    private var currentTick = 0
    private var gameStateWriter: GameStateWriter? = null
    private var eventWriter: GameStateEventWriter? = null
    private val chunksDir = File(sessionDir, "chunks")
    private val sessionStartTimeMs = System.currentTimeMillis()

    @Volatile
    private var sessionEndedAtMs: Long? = null

    // Thread-safe tracking of completed chunks for session manifest
    private data class ChunkEntry(
        val index: Int,
        val startTick: Int,
        val endTick: Int,
        @Volatile var uploaded: Boolean
    )

    private val completedChunks = mutableListOf<ChunkEntry>()
    private val manifestLock = Any()

    /**
     * Called at session start — opens chunk_0000 and writes the initial session manifest.
     */
    fun start() {
        try {
            chunksDir.mkdirs()
        } catch (e: Exception) {
            println("[MCAP ML] Warning: failed to create chunks directory: ${e.message}")
        }
        currentChunkStartTimeMs = System.currentTimeMillis()
        openChunk(0)
        synchronized(manifestLock) {
            writeSessionManifestFile()
        }
    }

    /**
     * Called every tick by MlSessionManager.
     *
     * @param client the Minecraft client
     * @param player the local player
     * @param tick absolute tick number since session start
     */
    fun onTick(client: MinecraftClient, player: ClientPlayerEntity, tick: Int) {
        currentTick = tick
        if (tick - currentChunkStartTick >= CHUNK_SIZE_TICKS) {
            rollChunk(tick)
        }
        gameStateWriter?.writeTick(client, player, tick)
        eventWriter?.checkInventory(player, tick)
    }

    /**
     * Called for each event (death, craft, dimension change, etc.).
     * Delegates to the current [GameStateEventWriter].
     *
     * Usage: `chunkManager.writeEvent { writer, tick -> writer.writeDimensionChange(tick, dim) }`
     */
    fun writeEvent(block: (GameStateEventWriter, Int) -> Unit) {
        val writer = eventWriter ?: return
        block(writer, currentTick)
    }

    /**
     * Called at session end — closes and uploads the final partial chunk.
     */
    fun end(finalTick: Int) {
        currentTick = finalTick
        sessionEndedAtMs = System.currentTimeMillis()
        closeAndUploadChunk(
            currentChunkIndex,
            chunkDir(currentChunkIndex),
            currentChunkStartTick,
            finalTick
        )
    }

    /**
     * Close current chunk, upload it, open next chunk.
     */
    private fun rollChunk(currentTick: Int) {
        val endTick = currentTick - 1
        closeAndUploadChunk(
            currentChunkIndex,
            chunkDir(currentChunkIndex),
            currentChunkStartTick,
            endTick
        )
        currentChunkIndex++
        currentChunkStartTick = currentTick
        currentChunkStartTimeMs = System.currentTimeMillis()
        openChunk(currentChunkIndex)
    }

    /**
     * Open writers in a new chunk directory.
     */
    private fun openChunk(index: Int) {
        val dir = chunkDir(index)
        try {
            dir.mkdirs()
        } catch (e: Exception) {
            println("[MCAP ML] Warning: failed to create chunk directory ${dir.path}: ${e.message}")
            return
        }
        gameStateWriter = GameStateWriter(File(dir, "gamestate.bin")).also { it.open() }
        eventWriter = GameStateEventWriter(File(dir, "gamestate_events.bin")).also { it.open() }
    }

    /**
     * Close current writers and trigger background upload.
     */
    private fun closeAndUploadChunk(
        chunkIndex: Int,
        chunkDir: File,
        startTick: Int,
        endTick: Int
    ) {
        val endTimeMs = System.currentTimeMillis()

        // Close writers
        gameStateWriter?.close()
        gameStateWriter = null
        eventWriter?.close()
        eventWriter = null

        // Write chunk manifest with uploaded: false
        writeChunkManifest(chunkDir, chunkIndex, startTick, endTick, currentChunkStartTimeMs, endTimeMs)

        // Track in session manifest as not yet uploaded
        synchronized(manifestLock) {
            completedChunks.add(ChunkEntry(chunkIndex, startTick, endTick, false))
            writeSessionManifestFile()
        }

        // Resolve JWT: prefer constructor token, fall back to cached token from auth
        val token = jwtToken.ifBlank { IodineAuthClient.getCachedToken() ?: "" }
        if (token.isBlank()) {
            println("[MCAP ML] Chunk $chunkIndex upload skipped: no JWT available")
            return
        }

        val capturedIndex = chunkIndex
        Thread {
            try {
                val success = S3Uploader.uploadChunkViaAuth(
                    chunkDir, sessionId, capturedIndex, runDir, token
                )
                if (success) {
                    updateChunkManifestUploaded(chunkDir)
                    synchronized(manifestLock) {
                        completedChunks.find { it.index == capturedIndex }?.uploaded = true
                        writeSessionManifestFile()
                    }
                    println("[MCAP ML] Chunk $capturedIndex uploaded successfully")
                }
            } catch (e: Exception) {
                println("[MCAP ML] Chunk $capturedIndex upload failed: ${e.message}")
            }
        }.apply {
            name = "MCAP-ML-Chunk-$sessionId-$capturedIndex"
            isDaemon = true
            start()
        }
    }

    /**
     * Returns File for chunk_NNNN directory, creates it if needed.
     */
    private fun chunkDir(index: Int): File {
        return File(chunksDir, "chunk_%04d".format(index))
    }

    /**
     * Write chunk manifest.json.
     */
    private fun writeChunkManifest(
        chunkDir: File,
        index: Int,
        startTick: Int,
        endTick: Int,
        startTimeMs: Long,
        endTimeMs: Long
    ) {
        val manifest = """
            |{
            |  "session_id": "$sessionId",
            |  "chunk_index": $index,
            |  "start_tick": $startTick,
            |  "end_tick": $endTick,
            |  "start_time_ms": $startTimeMs,
            |  "end_time_ms": $endTimeMs,
            |  "uploaded": false
            |}
        """.trimMargin()
        try {
            File(chunkDir, "manifest.json").writeText(manifest)
        } catch (e: Exception) {
            println("[MCAP ML] Warning: failed to write chunk manifest: ${e.message}")
        }
    }

    /**
     * Update chunk manifest.json to mark uploaded: true after successful upload.
     */
    private fun updateChunkManifestUploaded(chunkDir: File) {
        val manifestFile = File(chunkDir, "manifest.json")
        try {
            val content = manifestFile.readText()
            manifestFile.writeText(content.replace("\"uploaded\": false", "\"uploaded\": true"))
        } catch (e: Exception) {
            println("[MCAP ML] Warning: failed to update chunk manifest uploaded status: ${e.message}")
        }
    }

    /**
     * Update top-level session_manifest.json.
     * Must be called under [manifestLock].
     */
    private fun writeSessionManifestFile() {
        val chunksJson = if (completedChunks.isEmpty()) {
            ""
        } else {
            completedChunks.joinToString(",\n    ") { chunk ->
                """{"index": ${chunk.index}, "start_tick": ${chunk.startTick}, "end_tick": ${chunk.endTick}, "uploaded": ${chunk.uploaded}}"""
            }
        }
        val endedAtStr = sessionEndedAtMs?.toString() ?: "null"
        val manifest = """
            |{
            |  "session_id": "$sessionId",
            |  "player_id": "$playerId",
            |  "started_at_ms": $sessionStartTimeMs,
            |  "ended_at_ms": $endedAtStr,
            |  "total_chunks": ${completedChunks.size},
            |  "chunks": [
            |    $chunksJson
            |  ]
            |}
        """.trimMargin()
        try {
            File(sessionDir, "session_manifest.json").writeText(manifest)
        } catch (e: Exception) {
            println("[MCAP ML] Warning: failed to write session manifest: ${e.message}")
        }
    }
}
