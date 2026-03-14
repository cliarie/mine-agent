package dev.replaycraft.mcap.auth

import net.minecraft.client.MinecraftClient
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.*
import javax.crypto.Cipher

/**
 * Authenticates the local player against the iodine server using the
 * Minecraft session protocol (RSA key exchange + Mojang hasJoined) and
 * receives a short-lived JWT.
 *
 * The JWT is cached in memory and on disk so subsequent calls within the
 * validity window skip the handshake entirely.
 *
 * Thread-safe: all mutable state is guarded by [lock].
 */
object IodineAuthClient {

    private const val TOKEN_FILE_NAME = "iodine_auth.json"
    private const val EXPIRY_BUFFER_MS = 60 * 60 * 1000L // 1-hour safety margin

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val secureRandom = SecureRandom()
    private val lock = Any()

    // --- cached state (guarded by [lock]) ---
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiresAt: Long = 0L
    @Volatile private var cachedUuid: String? = null

    // --- run directory, captured on main thread ---
    @Volatile private var runDirectory: File? = null

    /** The iodine server base URL (no trailing slash). */
    fun serverUrl(): String {
        return System.getenv("IODINE_SERVER_URL") ?: "http://localhost:8080"
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Return a valid JWT, performing the full handshake only when necessary.
     * Must be called off the main thread (performs blocking I/O).
     *
     * Session values (username, accessToken, uuid) must have been captured
     * first via [captureSession] on the main thread.
     *
     * @return the JWT string, or `null` on failure.
     */
    fun authenticate(client: MinecraftClient): String? {
        synchronized(lock) {
            val existing = cachedToken
            if (existing != null && isTokenValid()) return existing
        }

        return try {
            doAuthenticate(client)
        } catch (e: Exception) {
            println("[Iodine Auth] Authentication failed: ${e.message}")
            null
        }
    }

    /**
     * Return the cached JWT if it is still valid, without performing any I/O.
     * Safe to call from any thread including the game thread.
     *
     * @return the JWT string, or `null` if no valid token is cached.
     */
    fun getCachedToken(): String? {
        synchronized(lock) {
            val token = cachedToken ?: return null
            return if (isTokenValid()) token else null
        }
    }

    /** Invalidate any cached token so the next call re-authenticates. */
    fun invalidate() {
        synchronized(lock) {
            cachedToken = null
            cachedExpiresAt = 0L
            cachedUuid = null
        }
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private fun doAuthenticate(client: MinecraftClient): String? {
        // Read session values — caller must ensure this is safe (either main thread,
        // or values are still valid on the session object which is immutable per login)
        val session = client.session
        val username = session.username
        val accessToken = session.accessToken
        val uuid = session.uuidOrNull?.toString()
            ?: run { println("[Iodine Auth] No UUID available"); return null }
        val uuidNoDashes = uuid.replace("-", "")
        runDirectory = client.runDirectory

        // Dev bypass: if IODINE_DEV_TOKEN is set, use it directly without Mojang handshake
        val devToken = System.getenv("IODINE_DEV_TOKEN")
        if (!devToken.isNullOrBlank()) {
            val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L) // 24 hours
            synchronized(lock) {
                cachedToken = devToken
                cachedExpiresAt = expiresAt
                cachedUuid = uuid
            }
            saveToken(devToken, expiresAt, uuid)
            println("[Iodine Auth] Using dev token from IODINE_DEV_TOKEN env var")
            return devToken
        }

        val base = serverUrl()

        // 1. GET /minecraft/auth/start
        val startReq = HttpRequest.newBuilder()
            .uri(URI.create("$base/minecraft/auth/start"))
            .GET()
            .build()
        val startResp = httpClient.send(startReq, HttpResponse.BodyHandlers.ofString())
        if (startResp.statusCode() != 200) {
            println("[Iodine Auth] /auth/start failed: ${startResp.statusCode()}")
            return null
        }
        val startBody = startResp.body()
        val serverId = parseJsonString(startBody, "serverId")!!
        val publicKeyB64 = parseJsonString(startBody, "publicKey")!!
        val verifyTokenB64 = parseJsonString(startBody, "verifyToken")!!

        // Decode server's RSA public key
        val publicKeyBytes = Base64.getDecoder().decode(publicKeyB64)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))

        // Decode verify token
        val verifyTokenBytes = Base64.getDecoder().decode(verifyTokenB64)

        // Generate 16-byte shared secret
        val sharedSecret = ByteArray(16)
        secureRandom.nextBytes(sharedSecret)

        // RSA-encrypt shared secret and verify token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedSharedSecret = Base64.getEncoder().encodeToString(cipher.doFinal(sharedSecret))
        val encryptedVerifyToken = Base64.getEncoder().encodeToString(cipher.doFinal(verifyTokenBytes))

        // Compute Minecraft-style server hash (includes shared secret)
        val serverHash = computeServerHash(serverId, sharedSecret, publicKeyBytes)

        // 2. Join Mojang session server
        val joinJson = """{"accessToken":"$accessToken","selectedProfile":"$uuidNoDashes","serverId":"$serverHash"}"""
        val joinReq = HttpRequest.newBuilder()
            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(joinJson))
            .build()
        val joinResp = httpClient.send(joinReq, HttpResponse.BodyHandlers.ofString())
        if (joinResp.statusCode() != 204) {
            println("[Iodine Auth] Mojang join failed: ${joinResp.statusCode()} - ${joinResp.body()}")
            return null
        }

        // 3. POST /minecraft/auth/verify
        val verifyJson = """{"username":"$username","serverId":"$serverId","encryptedSharedSecret":"$encryptedSharedSecret","encryptedVerifyToken":"$encryptedVerifyToken"}"""
        val verifyReq = HttpRequest.newBuilder()
            .uri(URI.create("$base/minecraft/auth/verify"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(verifyJson))
            .build()
        val verifyResp = httpClient.send(verifyReq, HttpResponse.BodyHandlers.ofString())
        if (verifyResp.statusCode() != 200) {
            println("[Iodine Auth] /auth/verify failed: ${verifyResp.statusCode()} - ${verifyResp.body()}")
            return null
        }
        val verifyBody = verifyResp.body()
        val token = parseJsonString(verifyBody, "token")!!
        val expiresIn = parseJsonLong(verifyBody, "expiresIn")

        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

        synchronized(lock) {
            cachedToken = token
            cachedExpiresAt = expiresAt
            cachedUuid = uuid
        }

        saveToken(token, expiresAt, uuid)

        println("[Iodine Auth] Authenticated as $username — token expires in ${expiresIn}s")
        return token
    }

    // ------------------------------------------------------------------
    // Token persistence
    // ------------------------------------------------------------------

    private fun isTokenValid(): Boolean {
        return cachedToken != null &&
                System.currentTimeMillis() < (cachedExpiresAt - EXPIRY_BUFFER_MS)
    }

    private fun tokenFile(): File? {
        val dir = runDirectory ?: return null
        return File(dir, TOKEN_FILE_NAME)
    }

    private fun saveToken(token: String, expiresAt: Long, uuid: String) {
        try {
            val file = tokenFile() ?: return
            val json = """{"token":"$token","expiresAt":$expiresAt,"uuid":"$uuid"}"""
            file.writeText(json)
        } catch (e: Exception) {
            println("[Iodine Auth] Failed to save token: ${e.message}")
        }
    }

    /**
     * Try to load a cached token from disk (called once at session start).
     * Call from the main thread to capture runDirectory safely.
     */
    fun tryLoadCached(client: MinecraftClient) {
        runDirectory = client.runDirectory
        try {
            val file = File(client.runDirectory, TOKEN_FILE_NAME)
            if (!file.exists()) return
            val text = file.readText()
            val token = parseJsonString(text, "token") ?: return
            val expiresAt = parseJsonLong(text, "expiresAt")
            val uuid = parseJsonString(text, "uuid") ?: return

            val currentUuid = client.session.uuidOrNull?.toString()
            synchronized(lock) {
                cachedToken = token
                cachedExpiresAt = expiresAt
                cachedUuid = uuid
            }
            if (currentUuid != uuid || !isTokenValid()) {
                invalidate()
            } else {
                println("[Iodine Auth] Loaded cached token from disk")
            }
        } catch (_: Exception) { /* ignore corrupt file */ }
    }

    // ------------------------------------------------------------------
    // Minecraft server-hash computation
    // ------------------------------------------------------------------

    private fun computeServerHash(serverId: String, sharedSecret: ByteArray, publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(serverId.toByteArray(Charsets.US_ASCII))
        digest.update(sharedSecret)
        digest.update(publicKey)
        return minecraftHexDigest(digest.digest())
    }

    /**
     * Minecraft's non-standard hex digest: two's-complement signed interpretation
     * of the SHA-1 hash, printed in base-16 with a leading minus if negative.
     */
    private fun minecraftHexDigest(hash: ByteArray): String {
        val negative = (hash[0].toInt() and 0x80) != 0
        val processed = if (negative) {
            val inv = hash.map { (it.toInt().inv() and 0xFF).toByte() }.toByteArray()
            var carry = 1
            for (i in inv.indices.reversed()) {
                val v = (inv[i].toInt() and 0xFF) + carry
                inv[i] = (v and 0xFF).toByte()
                carry = v shr 8
            }
            inv
        } else {
            hash
        }
        val hex = processed.joinToString("") { "%02x".format(it) }.trimStart('0')
        return if (negative) "-$hex" else hex
    }

    // ------------------------------------------------------------------
    // Minimal JSON helpers (no library dependency)
    // ------------------------------------------------------------------

    /** Extract a string value for the given key from a flat JSON object. */
    private fun parseJsonString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    /** Extract a numeric (long) value for the given key from a flat JSON object. */
    private fun parseJsonLong(json: String, key: String): Long {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
