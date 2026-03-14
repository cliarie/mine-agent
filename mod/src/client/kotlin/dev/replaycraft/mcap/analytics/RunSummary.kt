package dev.replaycraft.mcap.analytics

data class RunSummary(
    val runId: String,
    val sessionId: String,
    val playerId: String,
    val category: String,
    val completed: Boolean,
    val outcome: String,
    val startedAtMs: Long,
    val durationTicks: Long,
    val overworldTicks: Long,
    val netherTicks: Long,
    val endTicks: Long,
    val portalBuildTick: Long,
    val fortressEnterTick: Long,
    val bastionEnterTick: Long,
    val strongholdEnterTick: Long,
    val blindTravelTick: Long,
    val eyeSpyTick: Long,
    val dragonEnterTick: Long,
    val killTick: Long,
    val blazeRodsUsed: Int,
    val blazeRodsCollected: Int,
    val blazeKills: Int,
    val pearlsUsed: Int,
    val bedsUsed: Int,
    val goldTraded: Int,
    val deaths: Int,
    val efficiencyScore: Int,
    val netherScore: Int,
    val seedQuality: Int,
    val modVersion: String
) {
    fun toJson(): String {
        return """
            |{
            |  "run_id": "${escapeJson(runId)}",
            |  "session_id": "${escapeJson(sessionId)}",
            |  "player_id": "${escapeJson(playerId)}",
            |  "category": "${escapeJson(category)}",
            |  "completed": $completed,
            |  "outcome": "${escapeJson(outcome)}",
            |  "started_at_ms": $startedAtMs,
            |  "duration_ticks": $durationTicks,
            |  "overworld_ticks": $overworldTicks,
            |  "nether_ticks": $netherTicks,
            |  "end_ticks": $endTicks,
            |  "portal_build_tick": $portalBuildTick,
            |  "fortress_enter_tick": $fortressEnterTick,
            |  "bastion_enter_tick": $bastionEnterTick,
            |  "stronghold_enter_tick": $strongholdEnterTick,
            |  "blind_travel_tick": $blindTravelTick,
            |  "eye_spy_tick": $eyeSpyTick,
            |  "dragon_enter_tick": $dragonEnterTick,
            |  "kill_tick": $killTick,
            |  "blaze_rods_used": $blazeRodsUsed,
            |  "blaze_rods_collected": $blazeRodsCollected,
            |  "blaze_kills": $blazeKills,
            |  "pearls_used": $pearlsUsed,
            |  "beds_used": $bedsUsed,
            |  "gold_traded": $goldTraded,
            |  "deaths": $deaths,
            |  "efficiency_score": $efficiencyScore,
            |  "nether_score": $netherScore,
            |  "seed_quality": $seedQuality,
            |  "mod_version": "${escapeJson(modVersion)}"
            |}
        """.trimMargin()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
