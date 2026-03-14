package dev.replaycraft.mcenv

import java.io.File
import java.util.Properties

data class EnvConfig(
    val enabled: Boolean,
    val port: Int,
    val waitForAction: Boolean
) {
    companion object {
        fun load(): EnvConfig {
            val props = Properties()
            val file = File("mine_env.properties")
            if (file.exists()) props.load(file.inputStream())
            return EnvConfig(
                enabled = props.getProperty("mine_env.enabled", "true").toBoolean(),
                port = props.getProperty("mine_env.port", "25576").toInt(),
                waitForAction = props.getProperty("mine_env.wait_for_action", "false").toBoolean()
            )
        }
    }
}
