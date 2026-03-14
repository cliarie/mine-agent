package dev.replaycraft.mcap.analytics

/**
 * Unit tests for RunTracker analytics accumulation and summary generation.
 *
 * Inventory delta hookup note (Step 3):
 * GameStateEventWriter.checkInventory() writes raw slot snapshots as binary
 * (tick + EVENT_INVENTORY_DELTA + item_name + count + prev_count) directly to
 * a DataOutputStream. It does NOT emit discrete callback events that other code
 * can listen to. Therefore, RecordingEventHandler.trackAnalyticsInventory() correctly
 * implements its own aggregate inventory diffing (same algorithm) and routes deltas
 * to RunTracker.onInventoryDelta(). This is the right approach — no modification
 * to GameStateEventWriter is needed.
 */
fun main() {
    `complete run with dragon kill produces WIN summary`()
    `quit mid-nether produces partial QUIT summary`()
    `death outcome tracks death count`()
    `efficiency score clamps to zero floor`()
    `beds only count in END phase`()
    `buildSummary generates unique run IDs`()
    `resource counter tracks all item deltas correctly`()
    println("ALL TESTS PASSED")
}

fun `complete run with dragon kill produces WIN summary`() {
    val tracker = RunTracker("sess-1", "player-1", "0.1.0")

    // ~3600 ticks in overworld
    repeat(3600) { tracker.onTick() }

    // Enter nether
    tracker.onDimensionChange("minecraft:the_nether")
    // ~3720 ticks in nether
    repeat(3720) { tracker.onTick() }

    // Use blaze rods and pearls (negative delta = consumed)
    tracker.onInventoryDelta("minecraft:blaze_rod", -7)
    tracker.onInventoryDelta("minecraft:ender_pearl", -12)

    // Enter the End
    tracker.onDimensionChange("minecraft:the_end")
    // ~400 ticks in end
    repeat(400) { tracker.onTick() }

    // Place a bed
    tracker.onBlockPlaced("minecraft:white_bed")

    // Kill the dragon
    tracker.onEntityKilled("minecraft:ender_dragon")

    val summary = tracker.buildSummary(RunOutcome.WIN)

    assert(summary.completed) { "Expected completed=true for WIN" }
    assert(summary.outcome == "win") { "Expected outcome=win, got ${summary.outcome}" }
    assert(summary.overworldTicks == 3600L) { "Expected overworldTicks=3600, got ${summary.overworldTicks}" }
    assert(summary.netherTicks == 3720L) { "Expected netherTicks=3720, got ${summary.netherTicks}" }
    assert(summary.endTicks == 400L) { "Expected endTicks=400, got ${summary.endTicks}" }
    assert(summary.durationTicks == 7720L) { "Expected durationTicks=7720, got ${summary.durationTicks}" }
    assert(summary.blazeRodsUsed == 7) { "Expected blazeRodsUsed=7, got ${summary.blazeRodsUsed}" }
    assert(summary.pearlsUsed == 12) { "Expected pearlsUsed=12, got ${summary.pearlsUsed}" }
    assert(summary.bedsUsed == 1) { "Expected bedsUsed=1, got ${summary.bedsUsed}" }
    assert(summary.killTick != -1L) { "Expected killTick set, got -1" }
    assert(summary.dragonEnterTick != -1L) { "Expected dragonEnterTick set, got -1" }
    assert(summary.portalBuildTick != -1L) { "Expected portalBuildTick set, got -1" }
    assert(summary.efficiencyScore in 0..100) { "Expected efficiencyScore in 0..100, got ${summary.efficiencyScore}" }
    assert(summary.sessionId == "sess-1") { "Expected sessionId=sess-1, got ${summary.sessionId}" }
    assert(summary.playerId == "player-1") { "Expected playerId=player-1, got ${summary.playerId}" }
    assert(summary.modVersion == "0.1.0") { "Expected modVersion=0.1.0, got ${summary.modVersion}" }

    // runId must be a valid UUID (contains 4 hyphens, 36 chars)
    assert(summary.runId.length == 36) { "Expected runId UUID length 36, got ${summary.runId.length}" }
    assert(summary.runId.count { it == '-' } == 4) { "Expected 4 hyphens in runId UUID" }

    println("PASS: complete run with dragon kill produces WIN summary")
}

fun `quit mid-nether produces partial QUIT summary`() {
    val tracker = RunTracker("sess-2", "player-2", "0.1.0")

    // ~1000 ticks in overworld
    repeat(1000) { tracker.onTick() }

    // Enter nether
    tracker.onDimensionChange("minecraft:the_nether")
    // ~500 ticks in nether
    repeat(500) { tracker.onTick() }

    val summary = tracker.buildSummary(RunOutcome.QUIT)

    assert(!summary.completed) { "Expected completed=false for QUIT" }
    assert(summary.outcome == "quit") { "Expected outcome=quit, got ${summary.outcome}" }
    assert(summary.netherTicks == 500L) { "Expected netherTicks=500, got ${summary.netherTicks}" }
    assert(summary.endTicks == 0L) { "Expected endTicks=0, got ${summary.endTicks}" }
    assert(summary.killTick == -1L) { "Expected killTick=-1, got ${summary.killTick}" }
    assert(summary.dragonEnterTick == -1L) { "Expected dragonEnterTick=-1, got ${summary.dragonEnterTick}" }
    assert(summary.portalBuildTick != -1L) { "Expected portalBuildTick set (entered nether), got -1" }

    println("PASS: quit mid-nether produces partial QUIT summary")
}

fun `death outcome tracks death count`() {
    val tracker = RunTracker("sess-3", "player-3", "0.1.0")

    // ~2000 ticks in overworld
    repeat(2000) { tracker.onTick() }

    // Two deaths
    tracker.onPlayerDeath()
    tracker.onPlayerDeath()

    val summary = tracker.buildSummary(RunOutcome.DEATH)

    assert(!summary.completed) { "Expected completed=false for DEATH" }
    assert(summary.outcome == "death") { "Expected outcome=death, got ${summary.outcome}" }
    assert(summary.deaths == 2) { "Expected deaths=2, got ${summary.deaths}" }

    println("PASS: death outcome tracks death count")
}

fun `efficiency score clamps to zero floor`() {
    val tracker = RunTracker("sess-4", "player-4", "0.1.0")

    // Massive overconsumption: 20 blaze rods, 30 pearls
    tracker.onInventoryDelta("minecraft:blaze_rod", -20)
    tracker.onInventoryDelta("minecraft:ender_pearl", -30)

    val summary = tracker.buildSummary(RunOutcome.WIN)

    // efficiencyScore = 100 - (20-7)*5 - (30-12)*2 = 100 - 65 - 36 = -1 → clamped to 0
    assert(summary.efficiencyScore == 0) { "Expected efficiencyScore=0 (clamped), got ${summary.efficiencyScore}" }

    println("PASS: efficiency score clamps to zero floor")
}

fun `beds only count in END phase`() {
    val tracker = RunTracker("sess-5", "player-5", "0.1.0")

    // Place bed in overworld — should NOT count
    tracker.onBlockPlaced("minecraft:white_bed")

    // Enter the End
    tracker.onDimensionChange("minecraft:the_end")

    // Place bed in End — should count
    tracker.onBlockPlaced("minecraft:white_bed")

    val summary = tracker.buildSummary(RunOutcome.WIN)

    assert(summary.bedsUsed == 1) { "Expected bedsUsed=1 (only END bed), got ${summary.bedsUsed}" }

    println("PASS: beds only count in END phase")
}

fun `buildSummary generates unique run IDs`() {
    val tracker1 = RunTracker("sess-a", "player-a", "0.1.0")
    val tracker2 = RunTracker("sess-b", "player-b", "0.1.0")

    val summary1 = tracker1.buildSummary(RunOutcome.QUIT)
    val summary2 = tracker2.buildSummary(RunOutcome.QUIT)

    assert(summary1.runId != summary2.runId) { "Expected different runIds, both were ${summary1.runId}" }

    println("PASS: buildSummary generates unique run IDs")
}

/**
 * Test 7: ResourceCounter direct delta feeding.
 * Confirms all counters increment correctly when fed inventory deltas directly.
 */
fun `resource counter tracks all item deltas correctly`() {
    val counter = ResourceCounter()

    // Collect blaze rods (positive delta)
    counter.onInventoryDelta("minecraft:blaze_rod", 9, Phase.NETHER)
    assert(counter.blazeRodsCollected == 9) { "Expected blazeRodsCollected=9, got ${counter.blazeRodsCollected}" }

    // Use blaze rods (negative delta)
    counter.onInventoryDelta("minecraft:blaze_rod", -7, Phase.NETHER)
    assert(counter.blazeRodsUsed == 7) { "Expected blazeRodsUsed=7, got ${counter.blazeRodsUsed}" }

    // Use pearls
    counter.onInventoryDelta("minecraft:ender_pearl", -5, Phase.OVERWORLD)
    assert(counter.pearlsUsed == 5) { "Expected pearlsUsed=5, got ${counter.pearlsUsed}" }

    // Deaths
    counter.onDeath()
    counter.onDeath()
    assert(counter.deaths == 2) { "Expected deaths=2, got ${counter.deaths}" }

    // Gold trading
    counter.onPiglinTrade(18)
    assert(counter.goldTraded == 18) { "Expected goldTraded=18, got ${counter.goldTraded}" }

    // Bed placed in overworld — should NOT count
    counter.onBlockPlaced("minecraft:white_bed", Phase.OVERWORLD)
    assert(counter.bedsPlaced == 0) { "Expected bedsPlaced=0 (overworld), got ${counter.bedsPlaced}" }

    // Bed placed in End — should count
    counter.onBlockPlaced("minecraft:red_bed", Phase.END)
    assert(counter.bedsPlaced == 1) { "Expected bedsPlaced=1 (end), got ${counter.bedsPlaced}" }

    // Untracked item — should not crash or affect counters
    counter.onInventoryDelta("minecraft:cobblestone", 64, Phase.OVERWORLD)
    counter.onInventoryDelta("minecraft:cobblestone", -32, Phase.OVERWORLD)

    println("PASS: resource counter tracks all item deltas correctly")
}
