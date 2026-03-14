package dev.replaycraft.mcenv.socket

import com.google.gson.Gson
import dev.replaycraft.mcenv.MineEnvClient
import dev.replaycraft.mcenv.input.AgentInputBuffer
import dev.replaycraft.mcenv.input.AgentInputState
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class EnvServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    private val gson = Gson()
    private var connection: WebSocket? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        connection = conn
        AgentInputState.active = true
        MineEnvClient.logger.info("Agent connected from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connection = null
        AgentInputState.active = false
        AgentInputState.current = null
        MineEnvClient.logger.info("Agent disconnected")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val action = gson.fromJson(message, ActionMessage::class.java)
            AgentInputBuffer.offer(action)
        } catch (e: Exception) {
            MineEnvClient.logger.warn("Invalid action message: ${e.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        MineEnvClient.logger.warn("EnvServer error: ${ex.message}")
    }

    override fun onStart() {
        MineEnvClient.logger.info("mine-env WebSocket server started on port $port")
    }

    fun sendObservation(obs: ObservationMessage) {
        connection?.send(gson.toJson(obs))
    }
}
