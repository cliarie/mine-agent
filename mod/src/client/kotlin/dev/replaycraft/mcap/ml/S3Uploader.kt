package dev.replaycraft.mcap.ml

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.asByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Uploads completed session files to S3.
 *
 * S3 bucket and region are read from environment variables:
 *   MCAP_S3_BUCKET  — target bucket name
 *   MCAP_S3_REGION  — AWS region (e.g. us-east-1)
 *
 * Alternatively, a config file at <runDir>/mcap_ml_config.properties can specify:
 *   s3.bucket=my-bucket
 *   s3.region=us-east-1
 *
 * Upload runs on Dispatchers.IO so it does not block the game thread.
 */
object S3Uploader {

    private val uploadScope = CoroutineScope(Dispatchers.IO)

    /**
     * Upload session files to S3 asynchronously.
     *
     * @param sessionDir directory containing the session files
     * @param sessionId UUID of the session
     * @param runDir Minecraft run directory (for config file lookup)
     */
    fun uploadSession(sessionDir: File, sessionId: String, runDir: File) {
        val config = loadConfig(runDir)
        val bucket = config.bucket
        val region = config.region

        if (bucket == null || region == null) {
            println("[MCAP ML] S3 upload skipped: bucket or region not configured")
            println("[MCAP ML] Set MCAP_S3_BUCKET and MCAP_S3_REGION env vars, or create mcap_ml_config.properties")
            return
        }

        val filesToUpload = listOf("packets.bin", "gamestate.bin", "gamestate_events.bin", "manifest.json")
        val existingFiles = filesToUpload.mapNotNull { name ->
            val file = File(sessionDir, name)
            if (file.exists()) file else null
        }

        if (existingFiles.isEmpty()) {
            println("[MCAP ML] S3 upload skipped: no files found in $sessionDir")
            return
        }

        uploadScope.launch {
            try {
                S3Client.fromEnvironment {
                    this.region = region
                }.use { s3 ->
                    for (file in existingFiles) {
                        val key = "raw/session_id=$sessionId/${file.name}"
                        println("[MCAP ML] Uploading ${file.name} to s3://$bucket/$key")

                        s3.putObject(PutObjectRequest {
                            this.bucket = bucket
                            this.key = key
                            this.body = file.asByteStream()
                        })
                    }
                    println("[MCAP ML] S3 upload complete for session $sessionId")
                }
            } catch (e: Exception) {
                println("[MCAP ML] S3 upload failed: ${e.message}")
            }
        }
    }

    private data class S3Config(val bucket: String?, val region: String?)

    private fun loadConfig(runDir: File): S3Config {
        // First check environment variables
        val envBucket = System.getenv("MCAP_S3_BUCKET")
        val envRegion = System.getenv("MCAP_S3_REGION")
        if (envBucket != null && envRegion != null) {
            return S3Config(envBucket, envRegion)
        }

        // Fall back to config file
        val configFile = File(runDir, "mcap_ml_config.properties")
        if (configFile.exists()) {
            try {
                val props = java.util.Properties()
                configFile.inputStream().use { props.load(it) }
                return S3Config(
                    bucket = props.getProperty("s3.bucket") ?: envBucket,
                    region = props.getProperty("s3.region") ?: envRegion
                )
            } catch (e: Exception) {
                println("[MCAP ML] Failed to load config: ${e.message}")
            }
        }

        return S3Config(envBucket, envRegion)
    }
}
