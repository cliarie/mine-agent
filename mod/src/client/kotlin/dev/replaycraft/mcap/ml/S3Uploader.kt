package dev.replaycraft.mcap.ml

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Uploads session files to S3 using multipart upload.
 *
 * Uses raw HTTP + AWS Signature V4 to avoid pulling in the heavy AWS SDK
 * (which has hundreds of transitive dependencies that conflict with MC).
 *
 * S3 paths follow the convention:
 *   raw/session_id={sessionId}/tick_stream.arrow
 *   raw/session_id={sessionId}/events.arrow
 *   raw/session_id={sessionId}/metadata.json
 *
 * Credentials are read from environment variables or a config file:
 *   AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, MCAP_S3_BUCKET
 *
 * If credentials are not configured, upload is silently skipped and files
 * remain on disk for manual upload.
 */
class S3Uploader {

    private val accessKey: String? = System.getenv("AWS_ACCESS_KEY_ID")
    private val secretKey: String? = System.getenv("AWS_SECRET_ACCESS_KEY")
    private val region: String = System.getenv("AWS_REGION") ?: "us-east-1"
    private val bucket: String? = System.getenv("MCAP_S3_BUCKET")

    /** Whether S3 upload is configured and available */
    val isConfigured: Boolean
        get() = !accessKey.isNullOrBlank() && !secretKey.isNullOrBlank() && !bucket.isNullOrBlank()

    /**
     * Upload a session directory to S3.
     * Uploads all files (tick_stream.arrow, events.arrow, metadata.json) in the session dir.
     *
     * @param sessionDir Directory containing the session files
     * @param sessionId Session identifier for the S3 key prefix
     * @return true if all uploads succeeded, false otherwise
     */
    fun uploadSession(sessionDir: File, sessionId: String): Boolean {
        if (!isConfigured) {
            println("[MCAP-ML] S3 not configured, skipping upload. Set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, MCAP_S3_BUCKET")
            return false
        }

        val files = sessionDir.listFiles() ?: return false
        var allSucceeded = true

        for (file in files) {
            if (!file.isFile) continue
            val s3Key = "raw/session_id=$sessionId/${file.name}"
            val contentType = when (file.extension) {
                "arrow" -> "application/vnd.apache.arrow.file"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }

            try {
                val success = uploadFile(file, s3Key, contentType)
                if (success) {
                    println("[MCAP-ML] Uploaded ${file.name} to s3://$bucket/$s3Key")
                } else {
                    println("[MCAP-ML] Failed to upload ${file.name}")
                    allSucceeded = false
                }
            } catch (e: Exception) {
                println("[MCAP-ML] Error uploading ${file.name}: ${e.message}")
                allSucceeded = false
            }
        }

        return allSucceeded
    }

    /**
     * Upload a single file to S3 using PUT Object with AWS Signature V4.
     * For files larger than 5MB, uses multipart upload.
     */
    private fun uploadFile(file: File, s3Key: String, contentType: String): Boolean {
        if (file.length() > 5 * 1024 * 1024) {
            return multipartUpload(file, s3Key, contentType)
        }

        val fileBytes = file.readBytes()
        return putObject(fileBytes, s3Key, contentType)
    }

    /**
     * Simple PUT Object for files <= 5MB.
     */
    private fun putObject(data: ByteArray, s3Key: String, contentType: String): Boolean {
        val now = Instant.now()
        val host = "$bucket.s3.$region.amazonaws.com"
        val url = "https://$host/$s3Key"

        val payloadHash = sha256Hex(data)
        val headers = signRequest("PUT", "/$s3Key", host, contentType, payloadHash, now, data.size.toLong())

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        for ((key, value) in headers) {
            conn.setRequestProperty(key, value)
        }
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Content-Length", data.size.toString())

        conn.outputStream.use { it.write(data) }

        val responseCode = conn.responseCode
        conn.disconnect()
        return responseCode in 200..299
    }

    /**
     * Multipart upload for files > 5MB.
     * Uses S3's CreateMultipartUpload / UploadPart / CompleteMultipartUpload APIs.
     */
    private fun multipartUpload(file: File, s3Key: String, contentType: String): Boolean {
        val host = "$bucket.s3.$region.amazonaws.com"
        val partSize = 5 * 1024 * 1024 // 5MB minimum part size

        // 1. Initiate multipart upload
        val uploadId = initiateMultipart(host, s3Key, contentType) ?: return false

        // 2. Upload parts
        val etags = mutableListOf<String>()
        val fileBytes = file.readBytes()
        var offset = 0
        var partNumber = 1

        while (offset < fileBytes.size) {
            val end = minOf(offset + partSize, fileBytes.size)
            val partData = fileBytes.copyOfRange(offset, end)

            val etag = uploadPart(host, s3Key, uploadId, partNumber, partData)
            if (etag == null) {
                abortMultipart(host, s3Key, uploadId)
                return false
            }
            etags.add(etag)
            partNumber++
            offset = end
        }

        // 3. Complete multipart upload
        return completeMultipart(host, s3Key, uploadId, etags)
    }

    private fun initiateMultipart(host: String, s3Key: String, contentType: String): String? {
        val now = Instant.now()
        val queryString = "uploads="
        val url = "https://$host/$s3Key?uploads"
        val payloadHash = sha256Hex(ByteArray(0))
        val headers = signRequest("POST", "/$s3Key", host, contentType, payloadHash, now, 0, queryString)

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        for ((key, value) in headers) {
            conn.setRequestProperty(key, value)
        }
        conn.setRequestProperty("Content-Type", contentType)

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            conn.disconnect()
            return null
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        // Parse upload ID from XML response
        val regex = "<UploadId>(.+?)</UploadId>".toRegex()
        return regex.find(responseBody)?.groupValues?.get(1)
    }

    private fun uploadPart(host: String, s3Key: String, uploadId: String, partNumber: Int, data: ByteArray): String? {
        val now = Instant.now()
        val queryString = "partNumber=$partNumber&uploadId=${urlEncode(uploadId)}"
        val url = "https://$host/$s3Key?$queryString"
        val payloadHash = sha256Hex(data)
        val headers = signRequest("PUT", "/$s3Key", host, "application/octet-stream", payloadHash, now, data.size.toLong(), queryString)

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        for ((key, value) in headers) {
            conn.setRequestProperty(key, value)
        }
        conn.setRequestProperty("Content-Length", data.size.toString())

        conn.outputStream.use { it.write(data) }

        val responseCode = conn.responseCode
        val etag = conn.getHeaderField("ETag")
        conn.disconnect()

        return if (responseCode in 200..299) etag else null
    }

    private fun completeMultipart(host: String, s3Key: String, uploadId: String, etags: List<String>): Boolean {
        val now = Instant.now()
        val queryString = "uploadId=${urlEncode(uploadId)}"
        val url = "https://$host/$s3Key?$queryString"

        val xml = buildString {
            append("<CompleteMultipartUpload>")
            for ((i, etag) in etags.withIndex()) {
                append("<Part><PartNumber>${i + 1}</PartNumber><ETag>$etag</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }
        val data = xml.toByteArray(StandardCharsets.UTF_8)
        val payloadHash = sha256Hex(data)
        val headers = signRequest("POST", "/$s3Key", host, "application/xml", payloadHash, now, data.size.toLong(), queryString)

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        for ((key, value) in headers) {
            conn.setRequestProperty(key, value)
        }
        conn.setRequestProperty("Content-Type", "application/xml")
        conn.setRequestProperty("Content-Length", data.size.toString())

        conn.outputStream.use { it.write(data) }

        val responseCode = conn.responseCode
        conn.disconnect()
        return responseCode in 200..299
    }

    private fun abortMultipart(host: String, s3Key: String, uploadId: String) {
        try {
            val now = Instant.now()
            val queryString = "uploadId=${urlEncode(uploadId)}"
            val url = "https://$host/$s3Key?$queryString"
            val payloadHash = sha256Hex(ByteArray(0))
            val headers = signRequest("DELETE", "/$s3Key", host, "", payloadHash, now, 0, queryString)

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            conn.responseCode // trigger request
            conn.disconnect()
        } catch (_: Exception) {}
    }

    /** URL-encode a value per AWS SigV4 rules (spaces as %20, not +) */
    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // --- AWS Signature V4 ---

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun signRequest(
        method: String,
        canonicalUri: String,
        host: String,
        contentType: String,
        payloadHash: String,
        now: Instant,
        contentLength: Long,
        queryString: String = "",
    ): Map<String, String> {
        val dateStamp = dateFormatter.format(now)
        val amzDate = dateTimeFormatter.format(now)
        val service = "s3"
        val credentialScope = "$dateStamp/$region/$service/aws4_request"

        val canonicalQueryString = if (queryString.isNotEmpty()) {
            queryString.split("&").sorted().joinToString("&")
        } else ""

        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))

        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(secretKey!!, dateStamp, region, service)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Authorization" to authorization,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Host" to host,
        )
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun getSignatureKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        var key = hmacSha256("AWS4$secretKey".toByteArray(StandardCharsets.UTF_8), dateStamp)
        key = hmacSha256(key, region)
        key = hmacSha256(key, service)
        key = hmacSha256(key, "aws4_request")
        return key
    }
}
