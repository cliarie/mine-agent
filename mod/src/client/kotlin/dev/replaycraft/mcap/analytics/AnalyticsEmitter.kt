package dev.replaycraft.mcap.analytics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AnalyticsEmitter(
    private val endpoint: String,
    private val apiKey: String
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun emit(summary: RunSummary) {
        if (endpoint.isBlank() || apiKey.isBlank()) return
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
            println("[MCAP Analytics] Analytics emit: run=${summary.runId} outcome=${summary.outcome} ticks=${summary.durationTicks} attempt=$attempt endpoint=$endpoint")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $apiKey")
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
