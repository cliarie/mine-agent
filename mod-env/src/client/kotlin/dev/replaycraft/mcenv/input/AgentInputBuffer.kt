package dev.replaycraft.mcenv.input

import dev.replaycraft.mcenv.socket.ActionMessage
import java.util.concurrent.ArrayBlockingQueue

// written by WebSocket thread, read by game thread
object AgentInputBuffer {
    private val queue = ArrayBlockingQueue<ActionMessage>(1)

    fun offer(action: ActionMessage) {
        queue.clear()
        queue.offer(action)
    }

    fun poll(): ActionMessage? = queue.poll()

    fun take(): ActionMessage = queue.take()
}
