package dev.replaycraft.mcap.analytics

enum class Phase { OVERWORLD, NETHER, END }

class PhaseTimer {
    private var currentPhase = Phase.OVERWORLD
    private var overworldTicks = 0L
    private var netherTicks = 0L
    private var endTicks = 0L

    fun tick() {
        when (currentPhase) {
            Phase.OVERWORLD -> overworldTicks++
            Phase.NETHER -> netherTicks++
            Phase.END -> endTicks++
        }
    }

    fun onDimensionChange(newDimension: String) {
        currentPhase = when (newDimension) {
            "minecraft:the_nether" -> Phase.NETHER
            "minecraft:the_end" -> Phase.END
            else -> Phase.OVERWORLD
        }
    }

    fun getOverworldTicks() = overworldTicks
    fun getNetherTicks() = netherTicks
    fun getEndTicks() = endTicks
    fun getCurrentPhase() = currentPhase
}
