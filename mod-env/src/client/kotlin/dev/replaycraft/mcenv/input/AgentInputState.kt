package dev.replaycraft.mcenv.input

import dev.replaycraft.mcenv.socket.ActionMessage

// holds the action currently being applied this tick
// written by game thread only
object AgentInputState {
    @Volatile var current: ActionMessage? = null
    @Volatile var active: Boolean = false
}
