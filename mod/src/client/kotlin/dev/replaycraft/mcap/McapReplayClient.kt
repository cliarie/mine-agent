package dev.replaycraft.mcap

import dev.replaycraft.mcap.capture.TickRingBuffer
import dev.replaycraft.mcap.native.NativeBridge
import dev.replaycraft.mcap.replay.ReplayController
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object McapReplayClient : ClientModInitializer {
    private val client: MinecraftClient get() = MinecraftClient.getInstance()

    private val captureBuffer = TickRingBuffer(capacity = 20 * 120) // ~2 minutes

    private lateinit var writer: CaptureWriter
    private val replay = ReplayController()

    private lateinit var keyToggleReplay: KeyBinding
    private lateinit var keyPlayPause: KeyBinding
    private lateinit var keyStep: KeyBinding
    private lateinit var keyExit: KeyBinding

    override fun onInitializeClient() {
        NativeBridge.ensureLoaded()

        writer = CaptureWriter(captureBuffer, client.runDirectory.absolutePath)

        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping {
            writer.stop()
        })

        keyToggleReplay = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.mcap_replay")
        )
        keyPlayPause = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.playpause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, "category.mcap_replay")
        )
        keyStep = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.step", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, "category.mcap_replay")
        )
        keyExit = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.mcap_replay.exit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ESCAPE, "category.mcap_replay")
        )

        // Handle keybindings on client tick
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            val player = client.player ?: return@EndTick

            while (keyToggleReplay.wasPressed()) {
                if (!replay.isActive) {
                    replay.start()
                } else {
                    replay.stop()
                }
            }

            if (replay.isActive) {
                while (keyPlayPause.wasPressed()) replay.togglePlayPause()
                while (keyStep.wasPressed()) replay.stepOneTick(client)
                while (keyExit.wasPressed()) replay.stop()

                replay.onClientTick(client)
            }
        })

        // Capture on world tick (true 20Hz game tick rate)
        ClientTickEvents.END_WORLD_TICK.register(ClientTickEvents.EndWorldTick { _ ->
            val player = client.player ?: return@EndWorldTick
            if (replay.isActive) return@EndWorldTick

            captureBuffer.tryWriteFromClient(client, player)
        })

        HudRenderCallback.EVENT.register(HudRenderCallback { drawContext, _ ->
            replay.renderHud(drawContext)
        })

        writer.start()
    }
}
