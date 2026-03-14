package dev.replaycraft.mcap.analytics

enum class RunOutcome { WIN, DEATH, QUIT }

class RunTracker(
    private val sessionId: String,
    private val playerId: String,
    private val modVersion: String
) {
    private val startTimeMs = System.currentTimeMillis()
    private var totalTicks = 0L

    val phaseTimer = PhaseTimer()
    val resourceCounter = ResourceCounter()

    var portalBuildTick = -1L
    var fortressEnterTick = -1L
    var bastionEnterTick = -1L
    var strongholdEnterTick = -1L
    var blindTravelTick = -1L
    var eyeSpyTick = -1L
    var dragonEnterTick = -1L
    var killTick = -1L

    fun onTick() {
        totalTicks++
        phaseTimer.tick()
    }

    fun onDimensionChange(newDimension: String) {
        phaseTimer.onDimensionChange(newDimension)
        if (newDimension == "minecraft:the_nether" && portalBuildTick == -1L)
            portalBuildTick = totalTicks
        if (newDimension == "minecraft:the_end" && dragonEnterTick == -1L)
            dragonEnterTick = totalTicks
    }

    fun onAdvancement(advancementId: String) {
        when (advancementId) {
            "minecraft:nether/find_bastion" ->
                if (bastionEnterTick == -1L) bastionEnterTick = totalTicks
            "minecraft:nether/find_fortress" ->
                if (fortressEnterTick == -1L) fortressEnterTick = totalTicks
            "minecraft:story/follow_ender_eye" ->
                if (eyeSpyTick == -1L) eyeSpyTick = totalTicks
            "minecraft:nether/fast_travel" ->
                if (blindTravelTick == -1L) blindTravelTick = totalTicks
        }
    }

    fun onInventoryDelta(itemId: String, delta: Int) =
        resourceCounter.onInventoryDelta(itemId, delta, phaseTimer.getCurrentPhase())

    fun onPlayerDeath() = resourceCounter.onDeath()

    fun onEntityKilled(entityType: String) {
        if (entityType == "minecraft:ender_dragon") killTick = totalTicks
        if (entityType == "minecraft:blaze") resourceCounter.onBlazeKill()
    }

    fun onBlockPlaced(blockId: String) =
        resourceCounter.onBlockPlaced(blockId, phaseTimer.getCurrentPhase())

    fun onPiglinTrade(goldGiven: Int) = resourceCounter.onPiglinTrade(goldGiven)

    fun buildSummary(outcome: RunOutcome): RunSummary {
        val rc = resourceCounter
        val completed = outcome == RunOutcome.WIN

        val efficiencyScore = (100
            - (rc.blazeRodsUsed - 7).coerceAtLeast(0) * 5
            - (rc.pearlsUsed - 12).coerceAtLeast(0) * 2
            - rc.deaths * 10).coerceIn(0, 100)

        val netherScore = if (phaseTimer.getNetherTicks() > 0)
            (100 - (phaseTimer.getNetherTicks() - 2880) / 144).coerceIn(0, 100).toInt()
        else -1

        return RunSummary(
            runId = java.util.UUID.randomUUID().toString(),
            sessionId = sessionId,
            playerId = playerId,
            category = "any_percent",
            completed = completed,
            outcome = outcome.name.lowercase(),
            startedAtMs = startTimeMs,
            durationTicks = totalTicks,
            overworldTicks = phaseTimer.getOverworldTicks(),
            netherTicks = phaseTimer.getNetherTicks(),
            endTicks = phaseTimer.getEndTicks(),
            portalBuildTick = portalBuildTick,
            fortressEnterTick = fortressEnterTick,
            bastionEnterTick = bastionEnterTick,
            strongholdEnterTick = strongholdEnterTick,
            blindTravelTick = blindTravelTick,
            eyeSpyTick = eyeSpyTick,
            dragonEnterTick = dragonEnterTick,
            killTick = killTick,
            blazeRodsUsed = rc.blazeRodsUsed,
            blazeRodsCollected = rc.blazeRodsCollected,
            blazeKills = rc.blazeKills,
            pearlsUsed = rc.pearlsUsed,
            bedsUsed = rc.bedsPlaced,
            goldTraded = rc.goldTraded,
            deaths = rc.deaths,
            efficiencyScore = efficiencyScore,
            netherScore = netherScore,
            seedQuality = -1,
            modVersion = modVersion
        )
    }
}
