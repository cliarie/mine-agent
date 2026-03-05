package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.MinecraftClientAccessor
import dev.replaycraft.mcap.native.NativeBridge
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientLoginNetworkHandler
import net.minecraft.network.ClientConnection
import net.minecraft.network.NetworkSide
import java.io.File
import java.time.Duration

/**
 * Manages the full packet replay lifecycle using a fake server connection.
 *
 * Architecture (matching ReplayMod's ReplayHandler):
 * 1. Disconnect from the real server (integrated or remote)
 * 2. Create a fake ClientConnection with NetworkSide.CLIENTBOUND
 * 3. Wrap it in an EmbeddedChannel with our ReplayPacketSender in the pipeline
 * 4. Set it as MinecraftClient's active connection
 * 5. Feed captured packets through the channel at tick rate
 * 6. The normal MC packet handling pipeline processes everything:
 *    - GameJoinS2CPacket creates the world and player
 *    - ChunkDataS2CPacket loads terrain
 *    - OpenScreenS2CPacket shows inventory/crafting/chests
 *    - All entity, block, and UI packets work naturally
 * 7. On replay end, disconnect and return to title screen
 */
class ReplayHandler {

    var isActive: Boolean = false
        private set

    var isPlaying: Boolean = false
        private set

    var tick: Int = 0
        private set

    var maxTick: Int = 0
        private set

    private var replayHandle: Long = -1
    private var sessionName: String = ""

    // Fake connection state
    private var fakeConnection: ClientConnection? = null
    private var channel: EmbeddedChannel? = null
    private var packetSender: ReplayPacketSender? = null

    // Session picker state
    private var availableSessions: List<File> = emptyList()
    private var selectedSessionIndex: Int = 0

    // Track whether the initial world has been set up via GameJoinS2CPacket
    private var worldLoaded: Boolean = false

    // Ticks to wait before starting packet dispatch (let MC process the terrain screen)
    private var setupDelayTicks: Int = 0

    /**
     * Start replay: find sessions, open the most recent one,
     * disconnect from real server, set up fake connection, and begin.
     */
    fun start() {
        val client = MinecraftClient.getInstance()
        val sessionsDir = File(client.runDirectory, "mcap_replay/sessions")

        if (!sessionsDir.exists()) {
            println("[MCAP] No sessions directory found")
            return
        }

        val sessions = sessionsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
        if (sessions.isNullOrEmpty()) {
            println("[MCAP] No sessions found")
            return
        }

        availableSessions = sessions.filter { session ->
            val chunksDir = File(session, "chunks")
            val chunkCount = chunksDir.listFiles()?.count { it.extension == "cap" } ?: 0
            chunkCount >= 1
        }

        if (availableSessions.isEmpty()) {
            println("[MCAP] No valid sessions with chunk data found")
            return
        }

        selectedSessionIndex = 0
        openSelectedSession()
    }

    private fun openSelectedSession() {
        // Close any existing replay handle
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }

        if (selectedSessionIndex < 0 || selectedSessionIndex >= availableSessions.size) return

        val session = availableSessions[selectedSessionIndex]
        sessionName = session.name

        try {
            replayHandle = NativeBridge.nativeOpenReplay(session.absolutePath)
        } catch (e: Exception) {
            println("[MCAP] Failed to open replay session: ${e.message}")
            return
        }
        if (replayHandle < 0) {
            println("[MCAP] nativeOpenReplay returned invalid handle for $sessionName")
            return
        }

        maxTick = NativeBridge.nativeGetReplayMaxTick(replayHandle)
        println("[MCAP] Opened session: $sessionName, maxTick=$maxTick")

        // Set up the fake connection and begin replay
        setupFakeConnection()
    }

    /**
     * Core of the ReplayHandler: disconnect from real server and set up
     * a fake ClientConnection with an EmbeddedChannel pipeline.
     */
    private fun setupFakeConnection() {
        val client = MinecraftClient.getInstance()

        // Create the packet sender that will feed captured packets
        packetSender = ReplayPacketSender(replayHandle)

        // Create a fake ClientConnection (like ReplayMod does)
        val networkManager = object : ClientConnection(NetworkSide.CLIENTBOUND) {
            override fun exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) {
                println("[MCAP] Replay connection exception: ${t.message}")
                t.printStackTrace()
            }
        }
        fakeConnection = networkManager

        // Set up the initial packet listener (login handler, will be replaced by GameJoin)
        // MC 1.20.1 signature: (ClientConnection, MinecraftClient, ServerInfo?, Screen?, boolean, Duration, Consumer<Text>)
        networkManager.setPacketListener(ClientLoginNetworkHandler(
            networkManager,
            client,
            null,  // serverInfo
            null,  // parentScreen
            false, // newWorld
            Duration.ZERO, // worldLoadTime
            { }    // statusConsumer
        ))

        // Create the EmbeddedChannel with our sender in the pipeline
        channel = EmbeddedChannel()
        channel!!.pipeline().addLast("mcap_replay_sender", packetSender)
        channel!!.pipeline().addLast("packet_handler", networkManager)
        channel!!.pipeline().fireChannelActive()

        // Disconnect from the real server/world
        client.disconnect()

        // Replace MC's connection with our fake one
        (client as MinecraftClientAccessor).mcap_setConnection(networkManager)

        // Mark replay as active
        isActive = true
        ReplayState.setReplayActive(true)
        isPlaying = false
        tick = 0
        worldLoaded = false
        setupDelayTicks = 0

        println("[MCAP] Fake connection established, starting packet replay for: $sessionName")
        println("[MCAP] Press G to play/pause, R to exit replay")

        // Send the initial batch of packets (tick 0) which should include GameJoinS2CPacket
        // This sets up the world, player entity, etc.
        sendInitialPackets()
    }

    /**
     * Send packets for tick 0 to bootstrap the world.
     * GameJoinS2CPacket will create the ClientPlayNetworkHandler,
     * which then processes subsequent packets.
     */
    private fun sendInitialPackets() {
        val sender = packetSender ?: return
        val count = sender.sendPacketsForTick(0)
        println("[MCAP] Sent $count initial packets (tick 0)")

        // Process the channel's pending messages
        channel?.let { ch ->
            while (ch.inboundMessages().isNotEmpty()) {
                ch.readInbound<Any>()
            }
        }

        // Also send a few more ticks worth of packets to ensure world is loaded
        // (chunk data, entity spawns, etc. may span multiple ticks)
        for (t in 1..20) {
            val n = sender.sendPacketsForTick(t)
            if (n > 0) {
                println("[MCAP] Sent $n packets for setup tick $t")
            }
        }

        // Process any pending messages again
        channel?.let { ch ->
            while (ch.inboundMessages().isNotEmpty()) {
                ch.readInbound<Any>()
            }
        }

        worldLoaded = true
        tick = 21 // Start playback after setup ticks
        println("[MCAP] World setup complete, ready for playback at tick $tick")
    }

    /**
     * Stop replay: close the fake connection, clean up, return to title screen.
     */
    fun stop() {
        val client = MinecraftClient.getInstance()

        // Close the replay handle
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }

        // Close the embedded channel
        channel?.close()
        channel = null
        packetSender = null
        fakeConnection = null

        isActive = false
        ReplayState.setReplayActive(false)
        isPlaying = false
        worldLoaded = false

        // Disconnect from the fake world and return to title
        client.disconnect()

        println("[MCAP] Replay stopped, returned to title screen")
    }

    fun togglePlayPause() {
        if (!isActive) return
        isPlaying = !isPlaying
        println("[MCAP] Replay ${if (isPlaying) "playing" else "paused"} at tick $tick")
    }

    fun stepOneTick(client: MinecraftClient) {
        if (!isActive || !worldLoaded) return
        if (isPlaying) return
        if (tick >= maxTick) return // Already at end, don't re-dispatch
        dispatchTickPackets(client)
        tick++
    }

    /**
     * Called every client tick while replay is active and playing.
     * Dispatches captured packets for the current tick.
     */
    fun onClientTick(client: MinecraftClient) {
        if (!isActive || !worldLoaded) return
        if (!isPlaying) return

        if (tick % 100 == 0) {
            println("[MCAP] Replay progress: tick $tick / $maxTick")
        }

        dispatchTickPackets(client)

        tick++
        if (tick >= maxTick) {
            println("[MCAP] Replay finished at tick $maxTick")
            isPlaying = false
            tick = maxTick
        }
    }

    /**
     * Dispatch all captured packets for the current tick through the fake connection.
     * Also applies tick record data (position/rotation/hotbar) from the 48-byte format.
     */
    private fun dispatchTickPackets(client: MinecraftClient) {
        val sender = packetSender ?: return

        // Send captured S2C packets for this tick
        val count = sender.sendPacketsForTick(tick)

        // Process the channel's pending messages
        channel?.let { ch ->
            while (ch.inboundMessages().isNotEmpty()) {
                ch.readInbound<Any>()
            }
        }

        // Also apply tick record data for position/rotation/hotbar
        // This ensures the camera follows the recorded first-person perspective
        applyTickRecord(client)
    }

    /**
     * Apply the 48-byte tick record for position, rotation, hotbar.
     * This provides smooth camera movement even if position packets
     * are missing or have lower resolution.
     */
    private fun applyTickRecord(client: MinecraftClient) {
        val player = client.player ?: return
        if (replayHandle < 0) return

        val record = try {
            NativeBridge.nativeReadTick(replayHandle, tick)
        } catch (e: Exception) {
            return
        }
        if (record.isEmpty()) return

        val buf = java.nio.ByteBuffer.wrap(record).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val flags = buf.short.toInt() and 0xFFFF
        val hotbar = buf.get().toInt() and 0xFF
        buf.get() // mouseBtn
        val yawFp = buf.short
        val pitchFp = buf.short
        buf.int // skip tick field
        val x = buf.float
        val y = buf.float
        val z = buf.float

        val yaw = yawFp.toFloat() / 100.0f
        val pitch = pitchFp.toFloat() / 100.0f

        if (x.isNaN() || y.isNaN() || z.isNaN() || (x == 0f && y == 0f && z == 0f)) return

        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()

        // Set position with full sync
        player.refreshPositionAndAngles(xd, yd, zd, yaw, pitch)
        player.prevX = xd
        player.prevY = yd
        player.prevZ = zd
        player.lastRenderX = xd
        player.lastRenderY = yd
        player.lastRenderZ = zd
        player.setVelocity(0.0, 0.0, 0.0)

        // Apply camera angles
        player.yaw = yaw
        player.pitch = pitch
        player.headYaw = yaw
        player.bodyYaw = yaw
        player.prevYaw = yaw
        player.prevPitch = pitch
        player.prevHeadYaw = yaw
        player.prevBodyYaw = yaw

        val acc = player as dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
        acc.mcap_setPrevYaw(yaw)
        acc.mcap_setPrevPitch(pitch)

        val cameraEntity = client.cameraEntity
        if (cameraEntity != null && cameraEntity == player) {
            cameraEntity.yaw = yaw
            cameraEntity.pitch = pitch
            cameraEntity.prevYaw = yaw
            cameraEntity.prevPitch = pitch
        }

        if (hotbar in 0..8) {
            player.inventory.selectedSlot = hotbar
        }

        // Handle arm swing from flags (bit 8)
        val armSwinging = (flags and (1 shl 8)) != 0
        if (armSwinging && !player.handSwinging) {
            player.swingHand(net.minecraft.util.Hand.MAIN_HAND)
        }
    }

    fun nextSession() {
        if (!isActive || availableSessions.isEmpty()) return
        val wasPlaying = isPlaying
        isPlaying = false

        // Close current replay
        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }
        channel?.close()
        channel = null
        packetSender = null

        selectedSessionIndex = (selectedSessionIndex + 1) % availableSessions.size
        openSelectedSession()
        if (wasPlaying) isPlaying = true
    }

    fun prevSession() {
        if (!isActive || availableSessions.isEmpty()) return
        val wasPlaying = isPlaying
        isPlaying = false

        if (replayHandle >= 0) {
            NativeBridge.nativeCloseReplay(replayHandle)
            replayHandle = -1
        }
        channel?.close()
        channel = null
        packetSender = null

        selectedSessionIndex = (selectedSessionIndex - 1 + availableSessions.size) % availableSessions.size
        openSelectedSession()
        if (wasPlaying) isPlaying = true
    }

    fun renderHud(ctx: net.minecraft.client.gui.DrawContext) {
        if (!isActive) return
        val status = if (isPlaying) "PLAY" else "PAUSE"
        val progress = if (maxTick > 0) "${(tick * 100 / maxTick)}%" else "0%"
        val sessionInfo = "(${selectedSessionIndex + 1}/${availableSessions.size})"
        val text = "REPLAY $status  $tick/$maxTick ($progress)"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, text, 8, 8, 0x00FF00, true)

        val sessionText = "Session $sessionInfo: $sessionName"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, sessionText, 8, 20, 0xFFFF00, true)

        val worldStatus = if (worldLoaded) "World loaded" else "Loading..."
        ctx.drawText(MinecraftClient.getInstance().textRenderer, worldStatus, 8, 32, 0xAAAAAA, true)

        val controls = "G=Play/Pause  .=Step  [/]=Prev/Next  V=Video  R=Exit"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, controls, 8, 44, 0xAAAAAA, true)
    }
}
