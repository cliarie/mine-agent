package dev.replaycraft.mcap.analytics

import dev.replaycraft.mcap.auth.IodineAuthClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts a [RunSummary] to the iodine server, authenticated with a JWT
 * obtained from [IodineAuthClient].
 */
class AnalyticsEmitter(
    private val jwtToken: String
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun emit(summary: RunSummary) {
        if (jwtToken.isBlank()) return
        Thread {
            doPost(summary, attempt = 1)
        }.apply {
            name = "MCAP-Analytics-${summary.runId}"
            isDaemon = true
            start()
        }
    }

    private fun doPost(summary: RunSummary, attempt: Int) {
        try {
            val body = summary.toJson()
            val endpoint = "${IodineAuthClient.serverUrl()}/minecraft/analytics"
            println("[MCAP Analytics] Analytics emit: run=${summary.runId} outcome=${summary.outcome} ticks=${summary.durationTicks} attempt=$attempt")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $jwtToken")
                .header("X-Mod-Version", summary.modVersion)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                println("[MCAP Analytics] Emitted run ${summary.runId} (${summary.outcome})")
            } else if (response.statusCode() >= 500 && attempt < 3) {
                Thread.sleep(1000L * attempt)
                doPost(summary, attempt + 1)
            } else {
                println("[MCAP Analytics] Emit failed (status ${response.statusCode()}) for run ${summary.runId}")
            }
        } catch (e: Exception) {
            if (attempt < 3) {
                Thread.sleep(1000L * attempt)
                doPost(summary, attempt + 1)
            } else {
                println("[MCAP Analytics] Emit failed after 3 attempts: ${e.message}")
            }
        }
    }
}
