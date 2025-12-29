package dev.replaycraft.mcap.replay

/**
 * Global replay state accessible from Mixins.
 * This allows Mixins to check if replay is active without circular dependencies.
 */
object ReplayState {
    @Volatile
    private var replayActive: Boolean = false
    
    fun setReplayActive(active: Boolean) {
        replayActive = active
    }
    
    fun isReplayActive(): Boolean = replayActive
}
