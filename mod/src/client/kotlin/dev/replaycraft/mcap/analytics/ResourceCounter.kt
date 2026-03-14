package dev.replaycraft.mcap.analytics

class ResourceCounter {
    var blazeRodsUsed = 0; private set
    var blazeRodsCollected = 0; private set
    var pearlsUsed = 0; private set
    var bedsPlaced = 0; private set
    var goldTraded = 0; private set
    var deaths = 0; private set

    fun onInventoryDelta(itemId: String, delta: Int, currentPhase: Phase) {
        when (itemId) {
            "minecraft:blaze_rod" -> {
                if (delta < 0) blazeRodsUsed += -delta
                else blazeRodsCollected += delta
            }
            "minecraft:ender_pearl" -> {
                if (delta < 0) pearlsUsed += -delta
            }
        }
    }

    fun onDeath() { deaths++ }

    fun onPiglinTrade(goldGiven: Int) { goldTraded += goldGiven }

    fun onBlockPlaced(blockId: String, currentPhase: Phase) {
        if (currentPhase == Phase.END && blockId.endsWith("_bed")) bedsPlaced++
    }
}
