package dev.replaycraft.mcap

import dev.replaycraft.mcap.capture.PacketCapture
import dev.replaycraft.mcap.capture.RawPacketCapture
import dev.replaycraft.mcap.capture.RecordingEventHandler
import dev.replaycraft.mcap.capture.TickRingBuffer
import dev.replaycraft.mcap.video.VideoRecorder
import dev.replaycraft.mcap.native.NativeBridge
import dev.replaycraft.mcap.ml.MlSessionManager
import dev.replaycraft.mcap.replay.McapReplayClientBridge
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object McapReplayClient : ClientModInitializer {
    private val client: MinecraftClient get() = MinecraftClient.getInstance()

    private val captureBuffer = TickRingBuffer(capacity = 20 * 120) // ~2 minutes

    private lateinit var writer: CaptureWriter

    private lateinit var keyPlayPause: KeyBinding
    private lateinit var keyStep: KeyBinding
    private lateinit var keyPrevSession: KeyBinding
    private lateinit var keyNextSession: KeyBinding
    private lateinit var keyRecordVideo: KeyBinding

    private val videoRecorder = VideoRecorder()

    override fun onInitializeClient() {
        NativeBridge.ensureLoaded()

        writer = CaptureWriter(captureBuffer, client.runDirectory.absolutePath)

        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping {
            MlSessionManager.stop(client)
            writer.stop()
        })

        // Replay controls (no R key for starting replay - replay is started from title screen Replay Center)
        keyPlayPause = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.playpause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.mcap_replay")
        )
        keyStep = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.step", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, "category.mcap_replay")
        )
        keyPrevSession = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.prev_session", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "category.mcap_replay")
        )
        keyNextSession = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.next_session", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.mcap_replay")
        )
        keyRecordVideo = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.record_video", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.mcap_replay")
        )

        // Track key states for direct GLFW input (more reliable)
        var lastStepKeyState = false
        var lastPlayPauseKeyState = false
        var lastPrevKeyState = false
        var lastNextKeyState = false
        var lastExitKeyState = false
        var wasScreenOpen = false // Track if a screen was open last tick (for Escape exit logic)
        
        // Track whether player was in a world last tick (to detect disconnect)
        var wasInWorld = false
        // Track whether ML session start has been attempted for current world
        var mlSessionStartAttempted = false
        
        // Handle keybindings on client tick
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            val window = client.window.handle
            val replay = McapReplayClientBridge.getActiveReplay()

            // Detect world disconnect: flush capture data so session appears in Replay Center
            val isInWorld = client.player != null && client.world != null
            if (wasInWorld && !isInWorld && (replay == null || !replay.isActive)) {
                println("[MCAP] World disconnected, flushing capture data")
                MlSessionManager.stop(client)
                writer.flush()
                mlSessionStartAttempted = false
            }
            // Detect world join: attempt to start ML capture
            if (isInWorld && !wasInWorld) {
                mlSessionStartAttempted = false
            }
            if (isInWorld && !mlSessionStartAttempted && (replay == null || !replay.isActive)) {
                mlSessionStartAttempted = true
                MlSessionManager.tryStart(client)
            }
            wasInWorld = isInWorld

            // ML tick processing (parallel to existing capture)
            if (isInWorld && MlSessionManager.isActive() && (replay == null || !replay.isActive)) {
                MlSessionManager.onTick(client)
            }

            if (replay != null && replay.isActive) {
                // Use direct GLFW key checking for all replay controls (more reliable)
                
                // Play/Pause (G)
                val playPauseKeyDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_G) == GLFW.GLFW_PRESS
                if (playPauseKeyDown && !lastPlayPauseKeyState) {
                    replay.togglePlayPause()
                }
                lastPlayPauseKeyState = playPauseKeyDown
                
                // Step (.)
                val stepKeyDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_PERIOD) == GLFW.GLFW_PRESS
                if (stepKeyDown && !lastStepKeyState) {
                    replay.stepOneTick(client)
                }
                lastStepKeyState = stepKeyDown
                
                // Prev session ([)
                val prevKeyDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_BRACKET) == GLFW.GLFW_PRESS
                if (prevKeyDown && !lastPrevKeyState) {
                    replay.prevSession()
                }
                lastPrevKeyState = prevKeyDown
                
                // Next session (])
                val nextKeyDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_BRACKET) == GLFW.GLFW_PRESS
                if (nextKeyDown && !lastNextKeyState) {
                    replay.nextSession()
                }
                lastNextKeyState = nextKeyDown

                // Exit replay (Escape key) - return to title screen
                // We must distinguish between "Escape to close a screen" and
                // "Escape to exit replay". If a screen was open last tick and
                // is now null, Escape just closed it — don't exit replay.
                // Only exit when Escape is pressed and no screen was open on
                // the previous tick either.
                val escapePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS
                val gameMenuOpened = client.currentScreen is GameMenuScreen
                val screenJustClosed = wasScreenOpen && client.currentScreen == null
                if (gameMenuOpened && !wasScreenOpen) {
                    // GameMenuScreen opened from no-screen state = user wants to exit
                    client.setScreen(null)
                    lastExitKeyState = escapePressed
                    wasScreenOpen = false
                    replay.stop()
                    return@EndTick
                } else if (escapePressed && !lastExitKeyState && client.currentScreen == null && !screenJustClosed) {
                    // Raw GLFW fallback: Escape pressed, no screen open, and no screen
                    // was open last tick (so this isn't a close-screen Escape)
                    lastExitKeyState = escapePressed
                    wasScreenOpen = false
                    replay.stop()
                    return@EndTick
                }
                lastExitKeyState = escapePressed
                wasScreenOpen = client.currentScreen != null

                replay.onClientTick(client)
            } else {
                // Reset replay control states when replay not active
                lastPlayPauseKeyState = false
                lastStepKeyState = false
                lastPrevKeyState = false
                lastNextKeyState = false
                lastExitKeyState = false
                wasScreenOpen = false
            }
        })

        // Capture on world tick (true 20Hz game tick rate)
        ClientTickEvents.END_WORLD_TICK.register(ClientTickEvents.EndWorldTick { _ ->
            val player = client.player ?: return@EndWorldTick
            val replay = McapReplayClientBridge.getActiveReplay()
            if (replay != null && replay.isActive) return@EndWorldTick

            // Tick-based client state capture (enhanced 48-byte format)
            captureBuffer.tryWriteFromClient(client, player)
            
            // Inject synthetic packets for local player state (position, equipment, animation)
            RecordingEventHandler.onPlayerTick()
            
            // Tick both capture systems
            PacketCapture.onTick()
            RawPacketCapture.onTick()
        })

        HudRenderCallback.EVENT.register(HudRenderCallback { drawContext, _ ->
            McapReplayClientBridge.getActiveReplay()?.renderHud(drawContext)
        })

        writer.start()
    }
}
