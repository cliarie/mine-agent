package dev.replaycraft.mcap.ml

import dev.replaycraft.mcap.auth.IodineAuthClient
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * Converts binary ML session files to Parquet and uploads to S3 via the
 * iodine server, authenticated with a JWT from [IodineAuthClient].
 *
 * Flow:
 *   1. Run convert_upload.py --convert-only to convert .bin files to .parquet
 *   2. POST to iodine /minecraft/upload with JWT auth and session_id
 *   3. Receive presigned S3 PUT URLs for tick_stream.parquet, events.parquet, manifest.json
 *   4. Upload each file via HTTP PUT to its presigned URL
 *
 * If conversion fails, the upload is skipped entirely (no partial sessions).
 *
 * The conversion and upload run on a background thread so they do not block the game thread.
 * Requires Python 3 + polars on the player's machine.
 */
object S3Uploader {

    /**
     * Convert and upload session files to S3 via iodine-authenticated presigned URLs (fire-and-forget).
     *
     * @param sessionDir directory containing the binary ML session files
     * @param sessionId UUID of the session
     * @param runDir Minecraft run directory (for script path resolution)
     * @param jwtToken iodine JWT token for authentication
     */
    fun uploadViaAuth(sessionDir: File, sessionId: String, runDir: File, jwtToken: String) {
        if (jwtToken.isBlank()) {
            println("[MCAP ML] S3 upload skipped: no iodine JWT available")
            return
        }

        println("[MCAP ML] Starting convert and upload for session $sessionId")

        // Run on background thread to avoid blocking the game thread
        Thread {
            try {
                doConvertAndUpload(jwtToken, sessionId, sessionDir, runDir)
            } catch (e: Exception) {
                println("[MCAP ML] Upload failed: ${e.message}")
            }
        }.apply {
            name = "MCAP-ML-Upload-$sessionId"
            isDaemon = true
            start()
        }
    }

    private fun doConvertAndUpload(
        jwtToken: String,
        sessionId: String,
        sessionDir: File,
        runDir: File
    ) {
        // 1. Run convert_upload.py --convert-only to convert .bin -> .parquet
        if (!runConversion(sessionDir, sessionId, runDir)) {
            println("[MCAP ML] Upload skipped: conversion failed for session $sessionId")
            return
        }

        // Verify all expected files exist before uploading (no partial sessions)
        val expectedFiles = listOf("tick_stream.parquet", "events.parquet", "manifest.json")
        val missingFiles = expectedFiles.filter { !File(sessionDir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            println("[MCAP ML] Upload skipped: missing files after conversion: ${missingFiles.joinToString(", ")}")
            return
        }

        // 2. POST to iodine server to get presigned upload URLs
        val requestBody = """{"session_id":"$sessionId"}"""

        val uploadEndpoint = "${IodineAuthClient.serverUrl()}/minecraft/upload"
        val conn = URI(uploadEndpoint).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $jwtToken")
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
            println("[MCAP ML] Server error ($responseCode): $errorMsg")
            return
        }

        // 3. Parse presigned URLs from response
        val responseText = conn.inputStream.bufferedReader().readText()
        val urls = parseUrls(responseText)
        if (urls.isEmpty()) {
            println("[MCAP ML] No presigned URLs received from server")
            return
        }

        println("[MCAP ML] Received ${urls.size} presigned URLs for session $sessionId")

        // 4. Upload each file via HTTP PUT
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
     * Parse the "urls" map from the server JSON response.
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
     * Run convert_upload.py --convert-only to convert binary session files to Parquet.
     *
     * @return true if conversion succeeded, false if it failed
     */
    private fun runConversion(sessionDir: File, sessionId: String, runDir: File): Boolean {
        val python = findPython()
        val script = findScript(runDir)

        if (script == null) {
            println("[MCAP ML] Conversion failed: convert_upload.py not found")
            return false
        }

        println("[MCAP ML] Running convert_upload.py --convert-only for session $sessionId")

        try {
            val process = ProcessBuilder(
                python, script.absolutePath, "--convert-only", sessionDir.absolutePath, sessionId
            )
                .redirectErrorStream(true)
                .start()

            // Read output for logging
            val output = process.inputStream.bufferedReader().readText()
            if (output.isNotBlank()) {
                output.lines().filter { it.isNotBlank() }.forEach { println(it) }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("[MCAP ML] convert_upload.py exited with code $exitCode")
                return false
            }

            println("[MCAP ML] Conversion completed successfully for session $sessionId")
            return true
        } catch (e: Exception) {
            println("[MCAP ML] Failed to run convert_upload.py: ${e.message}")
            return false
        }
    }

    /**
     * Find the Python 3 executable.
     */
    private fun findPython(): String {
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
        val envScript = System.getenv("MCAP_CONVERT_SCRIPT")
        if (envScript != null) {
            val f = File(envScript)
            if (f.exists()) return f
        }

        val extractedScript = File(runDir, "mcap_replay/convert_upload.py")
        if (extractedScript.exists()) return extractedScript

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
}
