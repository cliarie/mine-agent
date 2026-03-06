package dev.replaycraft.mcap.ml

import java.io.File

/**
 * Launches the Python convert_upload.py script to convert binary ML session
 * files to Parquet and upload to S3.
 *
 * S3 bucket and region are read from environment variables:
 *   MCAP_S3_BUCKET  — target bucket name
 *   MCAP_S3_REGION  — AWS region (e.g. us-east-1)
 *
 * Alternatively, a config file at <runDir>/mcap_ml_config.properties can specify:
 *   s3.bucket=my-bucket
 *   s3.region=us-east-1
 *
 * The script is invoked via ProcessBuilder (fire-and-forget) so it does not
 * block the game thread. Requires Python 3 + polars + boto3 on the player's machine.
 */
object S3Uploader {

    /**
     * Launch the convert & upload script asynchronously.
     *
     * @param sessionDir directory containing the ML session binary files
     * @param sessionId UUID of the session
     * @param runDir Minecraft run directory (for config file lookup and script path resolution)
     */
    fun launchConvertAndUpload(sessionDir: File, sessionId: String, runDir: File) {
        val config = loadConfig(runDir)
        val bucket = config.bucket
        val region = config.region

        if (bucket == null || region == null) {
            println("[MCAP ML] S3 upload skipped: bucket or region not configured")
            println("[MCAP ML] Set MCAP_S3_BUCKET and MCAP_S3_REGION env vars, or create mcap_ml_config.properties")
            return
        }

        val python = findPython()
        val script = findScript(runDir)

        if (script == null) {
            println("[MCAP ML] S3 upload skipped: convert_upload.py not found")
            return
        }

        println("[MCAP ML] Launching convert_upload.py for session $sessionId")

        try {
            ProcessBuilder(python, script.absolutePath, sessionDir.absolutePath, sessionId, bucket, region)
                .redirectErrorStream(true)
                .inheritIO()
                .start()
            // Fire and forget — don't block the game thread
        } catch (e: Exception) {
            println("[MCAP ML] Failed to launch convert_upload.py: ${e.message}")
        }
    }

    /**
     * Find the Python 3 executable.
     */
    private fun findPython(): String {
        // Check MCAP_PYTHON env var first, then fall back to python3
        return System.getenv("MCAP_PYTHON") ?: "python3"
    }

    /**
     * Find the convert_upload.py script.
     *
     * Search order:
     *   1. MCAP_CONVERT_SCRIPT env var (explicit override)
     *   2. Already-extracted copy at <runDir>/mcap_replay/convert_upload.py
     *   3. Extract from mod JAR classpath resource to <runDir>/mcap_replay/convert_upload.py
     */
    private fun findScript(runDir: File): File? {
        // 1. Check MCAP_CONVERT_SCRIPT env var
        val envScript = System.getenv("MCAP_CONVERT_SCRIPT")
        if (envScript != null) {
            val f = File(envScript)
            if (f.exists()) return f
        }

        // 2. Check if already extracted
        val extractedScript = File(runDir, "mcap_replay/convert_upload.py")
        if (extractedScript.exists()) return extractedScript

        // 3. Extract from classpath resource bundled in the mod JAR
        try {
            val resourceStream = S3Uploader::class.java.getResourceAsStream("/scripts/convert_upload.py")
            if (resourceStream != null) {
                extractedScript.parentFile?.mkdirs()
                resourceStream.use { input ->
                    extractedScript.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("[MCAP ML] Extracted convert_upload.py to ${extractedScript.absolutePath}")
                return extractedScript
            }
        } catch (e: Exception) {
            println("[MCAP ML] Failed to extract convert_upload.py from JAR: ${e.message}")
        }

        return null
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
                    bucket = envBucket ?: props.getProperty("s3.bucket"),
                    region = envRegion ?: props.getProperty("s3.region")
                )
            } catch (e: Exception) {
                println("[MCAP ML] Failed to load config: ${e.message}")
            }
        }

        return S3Config(envBucket, envRegion)
    }
}
