package dev.replaycraft.mcap.mcsr

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import org.xerial.snappy.Snappy
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Parser for MCSR Ranked 5.7.3 replay files.
 *
 * MCSR replay files are ZIP archives containing:
 *   - meta.json: match metadata (version, seeds, players, symmetric key)
 *   - timelines.json: match split/timeline display data
 *   - <UUID>: per-player encrypted timeline binary data
 *
 * Encryption: The symmetric key in meta.json is RSA-encrypted (with the MCSR
 * server's private key). We decrypt it using the public key (embedded in the
 * MCSR JAR), then use AES/ECB/PKCS5Padding to decrypt player timeline data.
 * For version >= 32, data is also Snappy-compressed.
 */
object McsrReplayFile {

    private val GSON = Gson()

    /**
     * MCSR Ranked replay RSA public key (extracted from AntiCheatConfig).
     * Used to decrypt the symmetric AES key stored in meta.json.
     */
    private const val REPLAY_PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvrJFV3KHy8jXWTok/3R3CHLqY6PPXoGjx8vCaAzuZgW/" +
        "7ZP6Dp95IkSEQioszuYJs6yawag+rZVzuVMjtZ9v8jVXeBw+nZdftsiSIJjOrL7BpJksBOGrKQp54SSV5Jel9ZVLff64" +
        "0lq5xMk/GgD+1hFtlBowd0deo9Y97GPRnmye9NpjCRQ5LZbOU9qxfDYNjjcjyc9EmAzSbWqZNfhjMBAAT2XoEeTKxCw" +
        "NW6BQ+UriL7b58nfV1bBG11bFR2fXPlkYCqjS1HEpsFPawwod58pZ75Aa7kvlTeOJ4MCejF2UBWLVrXK7BDyRyUqBcQH" +
        "RheX9BOxOU5GDa5fjSGOZBwIDAQAB"

    private val replayPublicKey: PublicKey by lazy {
        val keyBytes = Base64.getMimeDecoder().decode(REPLAY_PUBLIC_KEY_B64)
        val keySpec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    /**
     * Parse an MCSR replay file and return the loaded replay data.
     *
     * @param file The .mcsr replay ZIP file
     * @return Parsed replay data ready for playback
     * @throws Exception if the file cannot be parsed or decrypted
     */
    fun load(file: File): McsrReplayData {
        println("[MCSR] Loading replay file: ${file.name}")

        val zipFile = ZipFile(file)
        try {
            // 1. Parse meta.json
            val meta = parseMeta(zipFile)
            println("[MCSR] Meta: version=${meta.version}, matchId=${meta.matchId}, " +
                    "matchType=${meta.matchType}, players=${meta.players?.size ?: 0}, " +
                    "overworldSeed=${meta.overworldSeed}")

            // 2. Derive the AES symmetric key
            val secretKey = deriveSecretKey(meta.symmetricKey)
            val enableCompress = meta.version >= 32

            // 3. Parse timelines.json (optional, for display info)
            val matchTimelines = parseMatchTimelines(zipFile)

            // 4. For each player, decrypt and deserialize their timeline data
            val playerTimelines = mutableMapOf<UUID, Map<Int, List<McsrTimelineEvent>>>()
            var maxTick = 0

            for (player in meta.players.orEmpty()) {
                val uuid = try { UUID.fromString(player.uuid) } catch (_: Exception) { continue }
                val entryName = uuid.toString()

                val entry = zipFile.getEntry(entryName)
                if (entry == null) {
                    println("[MCSR] No timeline entry for player ${player.nickname} ($uuid)")
                    continue
                }

                try {
                    val encryptedBytes = zipFile.getInputStream(entry).readAllBytes()
                    val decrypted = decryptByteBuffer(secretKey, encryptedBytes, enableCompress)
                    val events = McsrTimelineDeserializer.deserialize(decrypted)
                    playerTimelines[uuid] = events
                    val playerMaxTick = McsrTimelineDeserializer.maxTick(events)
                    if (playerMaxTick > maxTick) maxTick = playerMaxTick
                    println("[MCSR] Player ${player.nickname}: ${events.size} tick entries, maxTick=$playerMaxTick")
                } catch (e: Exception) {
                    println("[MCSR] Failed to load timeline for ${player.nickname}: ${e.message}")
                    e.printStackTrace()
                }
            }

            if (playerTimelines.isEmpty()) {
                throw IllegalStateException("No player timeline data could be loaded")
            }

            return McsrReplayData(
                meta = meta,
                matchTimelines = matchTimelines,
                playerTimelines = playerTimelines,
                maxTick = maxTick
            )
        } finally {
            zipFile.close()
        }
    }

    /**
     * Check if a file appears to be an MCSR replay file.
     * Quick check without fully parsing: looks for meta.json in the ZIP.
     */
    fun isMcsrReplayFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val zipFile = ZipFile(file)
            val hasMeta = zipFile.getEntry("meta.json") != null
            zipFile.close()
            hasMeta
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Quick-load only the metadata from an MCSR replay file.
     * Useful for displaying file info without loading all timeline data.
     */
    fun loadMeta(file: File): McsrReplayMeta? {
        return try {
            val zipFile = ZipFile(file)
            val meta = parseMeta(zipFile)
            zipFile.close()
            meta
        } catch (_: Exception) {
            null
        }
    }

    // ---- Internal methods ----

    private fun parseMeta(zipFile: ZipFile): McsrReplayMeta {
        val entry = zipFile.getEntry("meta.json")
            ?: throw IllegalArgumentException("Not an MCSR replay file: missing meta.json")

        val json = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val jsonElement = JsonParser.parseString(json)
        val meta = GSON.fromJson(jsonElement, McsrReplayMeta::class.java)

        // Handle version migration (version < 30 has different format)
        if (meta.version < 30) {
            // For very old versions, try to migrate the format
            println("[MCSR] Warning: replay version ${meta.version} < 30, attempting compatibility mode")
        }

        return meta
    }

    private fun parseMatchTimelines(zipFile: ZipFile): List<McsrMatchTimeline> {
        val entry = zipFile.getEntry("timelines.json") ?: return emptyList()
        return try {
            val json = zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JsonParser.parseString(json).asJsonArray
            array.map { GSON.fromJson(it, McsrMatchTimeline::class.java) }
        } catch (e: Exception) {
            println("[MCSR] Failed to parse timelines.json: ${e.message}")
            emptyList()
        }
    }

    /**
     * Derive the AES SecretKey from the RSA-encrypted symmetric key in meta.json.
     *
     * Flow: Base64 decode → RSA decrypt with public key → AES SecretKeySpec
     *
     * MCSR encrypts the AES key with the server's RSA private key, so anyone
     * with the public key can decrypt it (this is by design for replay sharing).
     */
    private fun deriveSecretKey(symmetricKeyB64: String?): SecretKey {
        if (symmetricKeyB64.isNullOrEmpty()) {
            throw IllegalStateException("Replay file has no symmetric key")
        }

        val encryptedKeyBytes = Base64.getDecoder().decode(symmetricKeyB64)
        val rsaCipher = Cipher.getInstance("RSA")
        rsaCipher.init(Cipher.DECRYPT_MODE, replayPublicKey)
        val aesKeyBytes = rsaCipher.doFinal(encryptedKeyBytes)

        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Decrypt a ByteBuffer using AES/ECB/PKCS5Padding, optionally decompress with Snappy.
     * Matches MCSR's ReplayManager.decryptByteBuffer() exactly.
     */
    private fun decryptByteBuffer(key: SecretKey, data: ByteArray, compress: Boolean): ByteBuffer {
        val aesCipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aesCipher.init(Cipher.DECRYPT_MODE, key)
        val decrypted = aesCipher.doFinal(data)

        val result = if (compress) {
            Snappy.uncompress(decrypted)
        } else {
            decrypted
        }

        return ByteBuffer.wrap(result)
    }
}

// ---- Data classes for MCSR replay metadata ----

/**
 * Complete loaded MCSR replay data ready for playback.
 */
data class McsrReplayData(
    val meta: McsrReplayMeta,
    val matchTimelines: List<McsrMatchTimeline>,
    /** Player UUID → tick-indexed timeline events */
    val playerTimelines: Map<UUID, Map<Int, List<McsrTimelineEvent>>>,
    val maxTick: Int
)

/**
 * MCSR replay metadata from meta.json.
 * Mirrors com.mcsrranked.client.anticheat.replay.file.ReplayMeta.
 */
data class McsrReplayMeta(
    val version: Int = 0,
    val matchId: Int? = null,
    val date: Long = 0,
    val matchType: Int = 0,
    val overworldSeed: String? = null,
    val netherSeed: String? = null,
    val theEndSeed: String? = null,
    val symmetricKey: String? = null,
    val result: McsrMatchResult? = null,
    val players: List<McsrPlayer>? = null
) {
    /** Match type names from MCSR's MatchType enum */
    fun matchTypeName(): String = when (matchType) {
        0 -> "Casual"
        1 -> "Ranked"
        2 -> "Private"
        3 -> "Event"
        else -> "Unknown ($matchType)"
    }

    fun dateFormatted(): String {
        if (date == 0L) return "Unknown"
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            sdf.format(Date(date))
        } catch (_: Exception) {
            "Unknown"
        }
    }
}

data class McsrMatchResult(
    val uuid: String? = null,
    val time: Int = 0,
    val type: Int = 0
)

data class McsrPlayer(
    val uuid: String? = null,
    val nickname: String? = null
)

data class McsrMatchTimeline(
    val type: String? = null,
    val time: Int = 0,
    val uuid: String? = null
)
