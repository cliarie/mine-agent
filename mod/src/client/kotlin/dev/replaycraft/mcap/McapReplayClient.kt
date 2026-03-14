package dev.replaycraft.mcap

import dev.replaycraft.mcap.capture.RecordingEventHandler
import dev.replaycraft.mcap.ml.MlSessionManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object McapReplayClient : ClientModInitializer {
    private val client: MinecraftClient get() = MinecraftClient.getInstance()

    private lateinit var keyAnalyticsDebug: KeyBinding

    override fun onInitializeClient() {

        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping {
            MlSessionManager.stop(client)
        })

        keyAnalyticsDebug = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.analytics_debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.mcap_replay")
        )

        var lastF8KeyState = false

        // Track whether player was in a world last tick (to detect disconnect)
        var wasInWorld = false
        // Track whether ML session start has been attempted for current world
        var mlSessionStartAttempted = false
        
        // Handle keybindings on client tick
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            val window = client.window.handle

            // Detect world disconnect
            val isInWorld = client.player != null && client.world != null
            if (wasInWorld && !isInWorld) {
                println("[MCAP] World disconnected")
                MlSessionManager.stop(client)
                mlSessionStartAttempted = false
            }
            // Detect world join: attempt to start ML capture
            if (isInWorld && !wasInWorld) {
                mlSessionStartAttempted = false
            }
            if (isInWorld && !mlSessionStartAttempted) {
                if (MlSessionManager.tryStart(client)) {
                    mlSessionStartAttempted = true
                }
            }
            wasInWorld = isInWorld

            // ML tick processing
            if (isInWorld && MlSessionManager.isActive()) {
                MlSessionManager.onTick(client)
            }

            // F8 — dump live analytics state
            val f8Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS
            if (f8Down && !lastF8KeyState && isInWorld) {
                RecordingEventHandler.runTracker?.let { tracker ->
                    val phase = tracker.phaseTimer.getCurrentPhase()
                    val rc = tracker.resourceCounter
                    println("[MCAP Analytics Debug] session=${MlSessionManager.getSessionId()}")
                    println("  phase=$phase  overworld=${tracker.phaseTimer.getOverworldTicks()}  nether=${tracker.phaseTimer.getNetherTicks()}  end=${tracker.phaseTimer.getEndTicks()}")
                    println("  blazeRods=${rc.blazeRodsUsed}  blazeKills=${rc.blazeKills}  pearls=${rc.pearlsUsed}  beds=${rc.bedsPlaced}  deaths=${rc.deaths}  gold=${rc.goldTraded}")
                    println("  portalBuild=${tracker.portalBuildTick}  bastion=${tracker.bastionEnterTick}  fortress=${tracker.fortressEnterTick}  blind=${tracker.blindTravelTick}  stronghold=${tracker.strongholdEnterTick}  eyeSpy=${tracker.eyeSpyTick}  dragonEnter=${tracker.dragonEnterTick}  kill=${tracker.killTick}")
                } ?: println("[MCAP Analytics Debug] No active RunTracker")
            }
            lastF8KeyState = f8Down
        })
    }
}
