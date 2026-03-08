package dev.replaycraft.mcap.ml

import com.mojang.authlib.minecraft.MinecraftSessionService
import net.minecraft.client.MinecraftClient
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.util.UUID

/**
 * Uploads ML session files to S3 via mine-auth presigned URLs.
 *
 * Flow:
 *   1. Generate a random serverId (hex string)
 *   2. Call Minecraft's joinServer(serverId) to register session with Mojang
 *   3. POST to mine-auth /auth/begin with username, server_id, player_uuid, session_id
 *   4. Receive presigned S3 PUT URLs for tick_stream.parquet, events.parquet, manifest.json
 *   5. Upload each file via HTTP PUT to its presigned URL
 *
 * mine-auth URL is read from config file at <runDir>/mcap_ml_config.properties:
 *   mine_auth.url=http://localhost:8080
 *
 * Or from the MINE_AUTH_URL environment variable.
 *
 * The upload runs on a background thread so it does not block the game thread.
 */
object S3Uploader {

    private val secureRandom = SecureRandom()

    /**
     * Upload session files to S3 via mine-auth presigned URLs (fire-and-forget).
     *
     * @param sessionDir directory containing the converted Parquet/JSON session files
     * @param sessionId UUID of the session
     * @param runDir Minecraft run directory (for config file lookup)
     * @param client MinecraftClient for Mojang session authentication
     */
    fun uploadViaAuth(sessionDir: File, sessionId: String, runDir: File, client: MinecraftClient) {
        val authUrl = loadAuthUrl(runDir)
        if (authUrl == null) {
            println("[MCAP ML] S3 upload skipped: mine-auth URL not configured")
            println("[MCAP ML] Set MINE_AUTH_URL env var, or add mine_auth.url to mcap_ml_config.properties")
            return
        }

        // Capture all session values on the main thread (MinecraftClient is not thread-safe)
        val session = client.session
        val username = session.username
        val playerUuidObj = session.uuidOrNull
        if (playerUuidObj == null) {
            println("[MCAP ML] S3 upload skipped: player UUID not available")
            return
        }
        val playerUuid = playerUuidObj.toString()
        val accessToken = session.accessToken
        val sessionService = client.sessionService

        println("[MCAP ML] Starting authenticated upload for session $sessionId")

        // Run on background thread to avoid blocking the game thread
        Thread {
            try {
                doAuthenticatedUpload(authUrl, username, playerUuid, playerUuidObj, accessToken, sessionService, sessionId, sessionDir)
            } catch (e: Exception) {
                println("[MCAP ML] Upload failed: ${e.message}")
            }
        }.apply {
            name = "MCAP-ML-Upload-$sessionId"
            isDaemon = true
            start()
        }
    }

    private fun doAuthenticatedUpload(
        authUrl: String,
        username: String,
        playerUuid: String,
        playerUuidObj: UUID,
        accessToken: String,
        sessionService: MinecraftSessionService,
        sessionId: String,
        sessionDir: File
    ) {
        // 1. Generate random serverId
        val serverIdBytes = ByteArray(20)
        secureRandom.nextBytes(serverIdBytes)
        val serverId = serverIdBytes.joinToString("") { "%02x".format(it) }

        // 2. Join server via Mojang session service (all values captured on main thread)
        try {
            sessionService.joinServer(
                playerUuidObj,
                accessToken,
                serverId
            )
        } catch (e: Exception) {
            println("[MCAP ML] Mojang joinServer failed: ${e.message}")
            return
        }

        // 3. POST to mine-auth /auth/begin
        val requestBody = """
            |{
            |  "username": "$username",
            |  "server_id": "$serverId",
            |  "player_uuid": "$playerUuid",
            |  "session_id": "$sessionId"
            |}
        """.trimMargin()

        val authEndpoint = "${authUrl.trimEnd('/')}/auth/begin"
        val conn = URI(authEndpoint).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody) }

        val responseCode = conn.responseCode
        if (responseCode == 401 || responseCode == 403) {
            val errorMsg = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            println("[MCAP ML] Auth rejected ($responseCode): $errorMsg")
            return
        }
        if (responseCode != 200) {
            val errorMsg = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            println("[MCAP ML] mine-auth error ($responseCode): $errorMsg")
            return
        }

        // 4. Parse presigned URLs from response
        val responseText = conn.inputStream.bufferedReader().readText()
        val urls = parseUrls(responseText)
        if (urls.isEmpty()) {
            println("[MCAP ML] No presigned URLs received from mine-auth")
            return
        }

        println("[MCAP ML] Received ${urls.size} presigned URLs for session $sessionId")

        // 5. Upload each file via HTTP PUT
        for ((filename, presignedUrl) in urls) {
            val file = File(sessionDir, filename)
            if (!file.exists()) {
                println("[MCAP ML] File not found, skipping: $filename")
                continue
            }
            uploadFile(file, presignedUrl, filename)
        }

        println("[MCAP ML] Upload complete for session $sessionId")
    }

    /**
     * Upload a single file to a presigned S3 PUT URL.
     */
    private fun uploadFile(file: File, presignedUrl: String, filename: String) {
        val conn = URI(presignedUrl).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Content-Length", file.length().toString())
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000

        file.inputStream().use { input ->
            conn.outputStream.use { output ->
                input.copyTo(output)
            }
        }

        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            println("[MCAP ML] Uploaded $filename (${file.length()} bytes)")
        } else {
            val errorMsg = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            println("[MCAP ML] Failed to upload $filename ($responseCode): $errorMsg")
        }
    }

    /**
     * Parse the "urls" map from the mine-auth JSON response.
     * Uses simple string parsing to avoid adding a JSON library dependency.
     */
    private fun parseUrls(json: String): Map<String, String> {
        val urls = mutableMapOf<String, String>()
        // Find the "urls" object
        val urlsStart = json.indexOf("\"urls\"")
        if (urlsStart == -1) return urls

        val braceStart = json.indexOf('{', urlsStart)
        if (braceStart == -1) return urls

        // Find the matching closing brace
        var depth = 0
        var braceEnd = -1
        for (i in braceStart..json.lastIndex) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        braceEnd = i
                        break
                    }
                }
            }
        }
        if (braceEnd == -1) return urls

        val urlsBlock = json.substring(braceStart + 1, braceEnd)

        // Parse key-value pairs: "filename": "url"
        val pattern = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
        for (match in pattern.findAll(urlsBlock)) {
            urls[match.groupValues[1]] = match.groupValues[2]
        }
        return urls
    }

    /**
     * Load the mine-auth URL from environment variable or config file.
     */
    private fun loadAuthUrl(runDir: File): String? {
        // Check environment variable first
        val envUrl = System.getenv("MINE_AUTH_URL")
        if (envUrl != null) return envUrl

        // Fall back to config file
        val configFile = File(runDir, "mcap_ml_config.properties")
        if (configFile.exists()) {
            try {
                val props = java.util.Properties()
                configFile.inputStream().use { props.load(it) }
                return props.getProperty("mine_auth.url")
            } catch (e: Exception) {
                println("[MCAP ML] Failed to load config: ${e.message}")
            }
        }

        return null
    }
}
