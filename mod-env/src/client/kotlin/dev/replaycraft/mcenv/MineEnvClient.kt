package dev.replaycraft.mcenv

import dev.replaycraft.mcenv.input.AgentInputBuffer
import dev.replaycraft.mcenv.input.AgentInputState
import dev.replaycraft.mcenv.mixin.MinecraftClientAccessor
import dev.replaycraft.mcenv.observation.ObservationCapture
import dev.replaycraft.mcenv.socket.EnvServer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import org.slf4j.LoggerFactory

object MineEnvClient : ClientModInitializer {
    val logger = LoggerFactory.getLogger("mine-env")
    private lateinit var envServer: EnvServer
    private var tickCounter = 0L

    override fun onInitializeClient() {
        val config = EnvConfig.load()

        if (!config.enabled) {
            logger.info("mine-env disabled (mine_env.enabled=false)")
            return
        }

        envServer = EnvServer(config.port)
        envServer.isReuseAddr = true
        envServer.start()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // prevent singleplayer from pausing when window loses focus
            if (AgentInputState.active && client.options.pauseOnLostFocus) {
                client.options.pauseOnLostFocus = false
                logger.info("Disabled pauseOnLostFocus for agent control")
            }

            if (!AgentInputState.active) return@register

            val action = if (config.waitForAction) {
                AgentInputBuffer.take()
            } else {
                AgentInputBuffer.poll() ?: AgentInputState.current
            }
            AgentInputState.current = action

            // trigger attack/use via MinecraftClient invoker
            if (action != null) {
                if (action.attack) {
                    (client as MinecraftClientAccessor).invokeDoAttack()
                }
                if (action.use) {
                    (client as MinecraftClientAccessor).invokeDoItemUse()
                }
                if (action.drop) {
                    client.player?.dropSelectedItem(false)
                }
            }

            val obs = ObservationCapture.capture(tickCounter++) ?: return@register
            envServer.sendObservation(obs)
        }

        logger.info("mine-env started on port ${config.port}")
    }
}
