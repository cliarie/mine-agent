package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.mixin.MinecraftClientAccessor
import dev.replaycraft.mcap.native.NativeBridge
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.ReferenceCountUtil
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.DownloadingTerrainScreen
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.gui.screen.ingame.CraftingScreen
import net.minecraft.client.network.ClientLoginNetworkHandler
import net.minecraft.network.ClientConnection
import net.minecraft.network.DecoderHandler
import net.minecraft.network.NetworkSide
import net.minecraft.network.NetworkState
import net.minecraft.network.PacketBundler
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.PacketEncoder
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket
import net.minecraft.client.gui.screen.TitleScreen
import java.io.File
import java.time.Duration

/**
 * Manages the full packet replay lifecycle using a fake server connection.
 *
 * Architecture (matching ReplayMod's ReplayHandler.setup()):
 *
 * 1. Create a fake ClientConnection(NetworkSide.CLIENTBOUND)
 * 2. Create an EmbeddedChannel with this pipeline (matching ReplayMod exactly):
 *    DropOutboundMessagesHandler -> decoder (DecoderHandler) -> encoder (PacketEncoder)
 *    -> bundler (PacketBundler) -> mcap_replay_sender (ReplayPacketSender)
 *    -> packet_handler (ClientConnection)
 * 3. Set network state to LOGIN, install ClientLoginNetworkHandler
 * 4. Set it as MinecraftClient's active connection
 * 5. Synthesize a LoginSuccessS2CPacket (not in capture data since we start
 *    recording at PLAY state) and fire it to trigger LOGIN->PLAY transition
 * 6. Fire raw ByteBuf PLAY-state packets through channel.pipeline().fireChannelRead()
 *    - DecoderHandler decodes them into Packet<?> objects
 *    - PacketBundler handles bundle splitting (prevents BundleSplitterPacket crashes)
 *    - ReplayPacketSender filters BAD_PACKETS
 *    - ClientConnection dispatches to the appropriate handler
 * 7. GameJoinS2CPacket creates the world and player
 * 8. All subsequent packets are processed naturally
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

    // Track GUI readiness: don't auto-play until the DownloadingTerrainScreen is gone
    // and the world is visually rendered. Like ReplayMod's hasWorldLoaded flag.
    private var guiReady: Boolean = false
    private var guiWaitTicks: Int = 0
    private val MAX_GUI_WAIT_TICKS = 100 // 5 seconds max wait

    // Whether to auto-play once GUI is ready. Set to true for initial load,
    // but preserved as wasPlaying for session switches so user's pause state is respected.
    private var autoPlayAfterGuiReady: Boolean = true


    /**
     * Start replay for a specific session directory (called from ReplayViewerScreen).
     */
    fun startSession(sessionDir: File) {
        val sessionsDir = sessionDir.parentFile

        // Build list of available sessions for prev/next switching
        availableSessions = sessionsDir?.listFiles()?.filter { it.isDirectory }
            ?.filter { session ->
                val chunksDir = File(session, "chunks")
                val chunkCount = chunksDir.listFiles()?.count { it.extension == "cap" } ?: 0
                chunkCount >= 1
            }
            ?.sortedByDescending { it.name } ?: listOf(sessionDir)

        // Find the selected session's index
        selectedSessionIndex = availableSessions.indexOfFirst { it.absolutePath == sessionDir.absolutePath }
        if (selectedSessionIndex < 0) selectedSessionIndex = 0

        openSelectedSession()
    }

    /**
     * Start replay: find sessions, open the most recent one,
     * disconnect from real server, set up fake connection, and begin.
     * (Legacy entry point, kept for backward compatibility)
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
     * Core setup matching ReplayMod's ReplayHandler.setup().
     *
     * Creates:
     * - Fake ClientConnection(NetworkSide.CLIENTBOUND)
     * - EmbeddedChannel with full pipeline:
     *   DropOutbound -> decoder -> encoder -> bundler -> replay_sender -> packet_handler
     * - Sets network state to LOGIN with ClientLoginNetworkHandler
     * - Sets as MC's active connection
     * - Fires initial packets (LoginSuccess triggers LOGIN->PLAY transition)
     */
    private fun setupFakeConnection() {
        val client = MinecraftClient.getInstance()

        // Clear chat (like ReplayMod does)
        client.inGameHud.chatHud.clear(false)

        // Create the packet sender that will filter captured packets
        packetSender = ReplayPacketSender(replayHandle)

        // Create a fake ClientConnection (exactly like ReplayMod)
        val networkManager = object : ClientConnection(NetworkSide.CLIENTBOUND) {
            override fun exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) {
                println("[MCAP] Replay connection exception: ${t.message}")
                t.printStackTrace()
            }
        }
        fakeConnection = networkManager

        // Create the EmbeddedChannel with full pipeline matching ReplayMod:
        // DropOutbound -> decoder -> encoder -> bundler -> replay_sender -> packet_handler
        channel = EmbeddedChannel()
        channel!!.pipeline().addFirst("mcap_drop_outbound", DropOutboundMessagesHandler())
        channel!!.pipeline().addLast("decoder", DecoderHandler(NetworkSide.CLIENTBOUND))
        channel!!.pipeline().addLast("encoder", PacketEncoder(NetworkSide.SERVERBOUND))
        channel!!.pipeline().addLast("bundler", PacketBundler(NetworkSide.CLIENTBOUND))
        channel!!.pipeline().addLast("mcap_replay_sender", packetSender)
        channel!!.pipeline().addLast("packet_handler", networkManager)
        channel!!.pipeline().fireChannelActive()

        // Set network state to LOGIN (like ReplayMod: networkManager.setState(NetworkState.LOGIN))
        // MC transitions from handshake to login via packets normally, but we have no server
        // so we must switch manually.
        networkManager.setState(NetworkState.LOGIN)

        // Set the initial packet listener (login handler - will transition to play handler
        // when LoginSuccessS2CPacket is received through the pipeline)
        networkManager.setPacketListener(ClientLoginNetworkHandler(
            networkManager,
            client,
            null,  // serverInfo
            null,  // parentScreen
            false, // newWorld
            Duration.ZERO, // worldLoadTime
            { }    // statusConsumer
        ))

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
        guiReady = false
        guiWaitTicks = 0

        // Reset interpolation and screen state for fresh session
        prevTickX = Double.NaN
        prevTickY = Double.NaN
        prevTickZ = Double.NaN
        prevTickYaw = Float.NaN
        prevTickPitch = Float.NaN
        prevScreenType = 0

        println("[MCAP] Fake connection established with full ReplayMod pipeline for: $sessionName")

        // Our capture system starts recording at onGameJoin (PLAY state), so there's no
        // LoginSuccessS2CPacket in the capture data. We must synthesize one to trigger
        // the LOGIN->PLAY state transition before firing any PLAY-state packets.
        // Without this, the DecoderHandler tries to decode PLAY packets with the LOGIN
        // codec (which only has ~5 packet types) and throws IndexOutOfBoundsException.
        sendSyntheticLoginSuccess(networkManager)

        // Now fire the captured PLAY-state packets (starting with GameJoinS2CPacket)
        sendInitialPackets()
    }

    /**
     * Synthesize a LoginSuccessS2CPacket and fire it through the pipeline.
     * This triggers the LOGIN->PLAY state transition so that subsequent
     * PLAY-state packets are decoded correctly by the DecoderHandler.
     *
     * ReplayMod doesn't need this because they record from connection start
     * (including LOGIN packets). Our capture starts at onGameJoin (PLAY state).
     */
    private fun sendSyntheticLoginSuccess(networkManager: ClientConnection) {
        val client = MinecraftClient.getInstance()
        val pipe = channel?.pipeline() ?: return

        // Build a GameProfile for the synthetic LoginSuccessS2CPacket
        val uuid = try {
            java.util.UUID.fromString(client.session.uuid)
        } catch (_: Exception) {
            java.util.UUID.randomUUID()
        }
        val profile = com.mojang.authlib.GameProfile(uuid, client.session.username)
        val loginSuccessPacket = LoginSuccessS2CPacket(profile)

        // Encode the packet into MC wire format for LOGIN state CLIENTBOUND.
        // Wire format: [varint packetId][packet data]
        // In LOGIN state, LoginSuccessS2CPacket has packet ID 2.
        val packetBuf = PacketByteBuf(Unpooled.buffer())
        try {
            // Write the varint packet ID for LoginSuccessS2CPacket in LOGIN state
            // MC 1.20.1: LOGIN CLIENTBOUND packet ID 2
            packetBuf.writeVarInt(2)
            // Write the packet data
            loginSuccessPacket.write(packetBuf)

            // Fire through the pipeline - DecoderHandler will decode it,
            // ClientLoginNetworkHandler.onSuccess() will be called,
            // which transitions state to PLAY and creates ClientPlayNetworkHandler
            pipe.fireChannelRead(packetBuf)
            println("[MCAP] Fired synthetic LoginSuccessS2CPacket, LOGIN->PLAY transition triggered")
        } catch (e: Exception) {
            packetBuf.release()
            println("[MCAP] Failed to fire synthetic LoginSuccessS2CPacket: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Send packets for initial ticks to bootstrap the world.
     * Fires raw ByteBuf through the pipeline - DecoderHandler decodes them.
     * These are all PLAY-state packets (the LOGIN->PLAY transition has already happened).
     */
    private fun sendInitialPackets() {
        val sender = packetSender ?: return
        val pipe = channel?.pipeline() ?: return

        // Send first 20 ticks worth of packets to bootstrap the world,
        // but clamp to maxTick for short sessions (< 21 ticks)
        val setupEnd = minOf(20, maxTick)
        for (t in 0..setupEnd) {
            val n = sender.firePacketsForTick(pipe, t)
            if (n > 0) {
                println("[MCAP] Sent $n packets for setup tick $t")
            }
        }

        worldLoaded = true
        tick = setupEnd + 1 // Start playback after setup ticks (avoids re-dispatching last setup tick)

        // Close DownloadingTerrainScreen manually (matching ReplayMod lines 869-873).
        // MC shows this screen after GameJoinS2CPacket until PlayerPositionLookS2CPacket
        // arrives and the world finishes loading. In replay, we need to close it manually.
        val mc = MinecraftClient.getInstance()
        val currentScreen = mc.currentScreen
        if (currentScreen is DownloadingTerrainScreen) {
            mc.setScreen(null)
            println("[MCAP] Closed DownloadingTerrainScreen manually")
        }

        if (tick >= maxTick) {
            // Short session: all ticks already dispatched during setup
            tick = maxTick
            isPlaying = false
            guiReady = true
            println("[MCAP] World setup complete, short session fully dispatched (tick $tick)")
        } else {
            // Don't auto-play yet - wait for GUI to fully render.
            // The terrain needs a few frames to actually appear on screen.
            isPlaying = false
            guiReady = false
            guiWaitTicks = 0
            autoPlayAfterGuiReady = true  // Default: auto-play for initial load
            println("[MCAP] World setup complete, waiting for GUI to render before auto-play...")
        }
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

        // Mark replay as inactive BEFORE disconnect so mixins stop interfering
        isActive = false
        ReplayState.setReplayActive(false)
        isPlaying = false
        worldLoaded = false
        guiReady = false

        // Clear the bridge reference so McapReplayClient stops checking this handler
        McapReplayClientBridge.clearActiveReplay()

        // Disconnect from the fake world BEFORE closing channel
        // (client.disconnect() needs the connection to still be functional)
        client.disconnect()

        // Now close the embedded channel
        channel?.close()
        channel = null
        packetSender = null
        fakeConnection = null

        // Explicitly navigate to title screen in case disconnect didn't
        client.setScreen(TitleScreen())

        println("[MCAP] Replay stopped, returned to title screen")
    }

    fun togglePlayPause() {
        if (!isActive) return
        // If at the end of replay, restart from beginning
        if (tick >= maxTick) {
            restartReplay()
            return
        }
        isPlaying = !isPlaying
        println("[MCAP] Replay ${if (isPlaying) "playing" else "paused"} at tick $tick")
    }

    /**
     * Restart replay from the beginning by re-opening the same session.
     * This creates a fresh fake connection and re-fires initial packets.
     */
    fun restartReplay() {
        if (!isActive) return
        println("[MCAP] Restarting replay from beginning...")

        // Close current channel but keep the handler active
        channel?.close()
        channel = null
        packetSender = null
        fakeConnection = null
        worldLoaded = false
        guiReady = false

        // Re-open the same session (creates fresh connection + initial packets)
        openSelectedSession()
        // If openSelectedSession() failed, stop to avoid broken state
        if (replayHandle < 0) {
            stop()
            return
        }
    }

    fun stepOneTick(client: MinecraftClient) {
        if (!isActive || !worldLoaded || !guiReady) return
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

        // Wait for GUI to be ready before starting auto-play.
        // This ensures the terrain is visually rendered before playback begins,
        // not just loaded in the terminal. Matching ReplayMod's approach of
        // waiting for DownloadingTerrainScreen to close + a few render frames.
        if (!guiReady) {
            guiWaitTicks++
            val screen = client.currentScreen

            // Close DownloadingTerrainScreen if still showing
            if (screen is DownloadingTerrainScreen) {
                client.setScreen(null)
                println("[MCAP] Closed lingering DownloadingTerrainScreen")
            }

            // Wait until: no blocking screen + world exists + waited at least 10 ticks (0.5s)
            // for the renderer to catch up, OR timeout after MAX_GUI_WAIT_TICKS
            val noBlockingScreen = client.currentScreen == null
            val worldExists = client.world != null && client.player != null
            val rendererReady = guiWaitTicks >= 10
            val timedOut = guiWaitTicks >= MAX_GUI_WAIT_TICKS

            if ((noBlockingScreen && worldExists && rendererReady) || timedOut) {
                guiReady = true
                isPlaying = autoPlayAfterGuiReady
                println("[MCAP] GUI ready after $guiWaitTicks ticks, ${if (isPlaying) "auto-playing" else "paused"} from tick $tick")
            }
            return
        }

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
     * Fires raw ByteBuf through the pipeline - DecoderHandler decodes, PacketBundler
     * handles bundles, ReplayPacketSender filters, ClientConnection dispatches.
     */
    private fun dispatchTickPackets(client: MinecraftClient) {
        val sender = packetSender ?: return
        val pipe = channel?.pipeline() ?: return

        // Fire captured S2C packets for this tick through the pipeline
        sender.firePacketsForTick(pipe, tick)

        // Also apply tick record data for position/rotation/hotbar
        // This ensures the camera follows the recorded first-person perspective
        applyTickRecord(client)
    }

    // Track previous tick's position/rotation for smooth interpolation
    private var prevTickX = Double.NaN
    private var prevTickY = Double.NaN
    private var prevTickZ = Double.NaN
    private var prevTickYaw = Float.NaN
    private var prevTickPitch = Float.NaN

    // Track previous screen type for detecting open/close transitions
    private var prevScreenType: Int = 0

    /**
     * Apply the 48-byte tick record for position, rotation, hotbar.
     *
     * Smoothness improvement: Instead of snapping prevX/Y/Z to the current
     * position (which causes jerky movement), we set prev* fields to the
     * PREVIOUS tick's position. This allows MC's renderer to interpolate
     * smoothly between the previous and current positions using the partial
     * tick value (lerp factor). This matches ReplayMod's CameraEntity approach
     * where lastRenderX/Y/Z track the previous frame for interpolation.
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

        // Smoothness: Set prev* to the PREVIOUS tick's values so MC's renderer
        // can interpolate between prev and current using the partial tick fraction.
        // On first tick, snap prev to current (no interpolation data yet).
        val hasPrev = !prevTickX.isNaN()
        val pX = if (hasPrev) prevTickX else xd
        val pY = if (hasPrev) prevTickY else yd
        val pZ = if (hasPrev) prevTickZ else zd
        val pYaw = if (hasPrev) prevTickYaw else yaw
        val pPitch = if (hasPrev) prevTickPitch else pitch

        // Set current position
        player.refreshPositionAndAngles(xd, yd, zd, yaw, pitch)

        // Set interpolation fields to PREVIOUS tick's position for smooth lerp
        player.prevX = pX
        player.prevY = pY
        player.prevZ = pZ
        player.lastRenderX = pX
        player.lastRenderY = pY
        player.lastRenderZ = pZ
        player.setVelocity(0.0, 0.0, 0.0)

        // Apply camera angles with interpolation
        player.yaw = yaw
        player.pitch = pitch
        player.headYaw = yaw
        player.bodyYaw = yaw
        player.prevYaw = pYaw
        player.prevPitch = pPitch
        player.prevHeadYaw = pYaw
        player.prevBodyYaw = pYaw

        val acc = player as dev.replaycraft.mcap.mixin.EntityPrevAnglesAccessor
        acc.mcap_setPrevYaw(pYaw)
        acc.mcap_setPrevPitch(pPitch)

        val cameraEntity = client.cameraEntity
        if (cameraEntity != null && cameraEntity == player) {
            cameraEntity.yaw = yaw
            cameraEntity.pitch = pitch
            cameraEntity.prevYaw = pYaw
            cameraEntity.prevPitch = pPitch
        }

        if (hotbar in 0..8) {
            player.inventory.selectedSlot = hotbar
        }

        // Handle arm swing from flags (bit 8)
        val armSwinging = (flags and (1 shl 8)) != 0
        if (armSwinging && !player.handSwinging) {
            player.swingHand(net.minecraft.util.Hand.MAIN_HAND)
        }

        // --- Screen open/close from tick record ---
        // Read remaining bytes to get to screenType at byte 29
        // buf position after reading x,y,z is at byte 24
        // health (24-27), food (28), screenType (29)
        if (record.size >= 30) {
            val screenBuf = java.nio.ByteBuffer.wrap(record).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val screenType = (screenBuf.get(29).toInt() and 0xFF)

            if (screenType != prevScreenType) {
                applyScreenTransition(client, player, prevScreenType, screenType)
                prevScreenType = screenType
            }
        }

        // Store this tick's values as "previous" for next tick's interpolation
        prevTickX = xd
        prevTickY = yd
        prevTickZ = zd
        prevTickYaw = yaw
        prevTickPitch = pitch
    }

    /**
     * Open/close screens during replay based on the recorded screenType transitions.
     * screenType values from TickRingBuffer.classifyScreen():
     *   0=none, 1=inventory, 2=chest, 3=crafting, 4=furnace, 5=enchant,
     *   6=anvil, 7=brewing, 8=beacon, 9=merchant, 10=hopper, 11=shulker, 12=creative
     *
     * For client-side screens (inventory, creative), we directly open the screen.
     * For server-mediated screens (chest, crafting table, furnace, etc.), the
     * OpenScreenS2CPacket in the capture data handles it. We still handle the
     * close transition here for all screen types.
     */
    private fun applyScreenTransition(
        client: MinecraftClient,
        player: net.minecraft.entity.player.PlayerEntity,
        oldType: Int,
        newType: Int
    ) {
        try {
            if (newType == 0 && oldType != 0) {
                // Screen closed — close whatever is currently open
                if (client.currentScreen != null) {
                    client.setScreen(null)
                }
            } else if (newType != 0 && oldType == 0) {
                // Screen opened — open the appropriate screen
                when (newType) {
                    1 -> {
                        // Client-side inventory (E key)
                        client.setScreen(InventoryScreen(player))
                    }
                    12 -> {
                        // Creative inventory — only works if gamemode is creative
                        // MC opens CreativeInventoryScreen automatically for creative mode
                        client.setScreen(InventoryScreen(player))
                    }
                    // Types 2-11 are server-mediated (chest, crafting, furnace, etc.)
                    // They are handled by OpenScreenS2CPacket in the capture data.
                    // If no packet was captured (edge case), we can't open them
                    // because we don't have a valid ScreenHandler syncId.
                }
            } else if (newType != 0 && oldType != 0 && newType != oldType) {
                // Screen type changed (e.g., inventory → crafting) without closing.
                // Close old and open new.
                client.setScreen(null)
                when (newType) {
                    1 -> client.setScreen(InventoryScreen(player))
                    12 -> client.setScreen(InventoryScreen(player))
                }
            }
        } catch (e: Exception) {
            // Ignore screen transition errors
            println("[MCAP] Screen transition error: ${e.message}")
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
        fakeConnection = null

        selectedSessionIndex = (selectedSessionIndex + 1) % availableSessions.size
        openSelectedSession()
        // If openSelectedSession() failed, handler is left with replayHandle=-1
        // but isActive=true. Stop to avoid broken state (infinite tick increments).
        if (replayHandle < 0) {
            stop()
            return
        }
        // Respect user's play/pause state: set autoPlayAfterGuiReady so the
        // GUI wait loop uses the correct state instead of always auto-playing.
        autoPlayAfterGuiReady = wasPlaying
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
        fakeConnection = null

        selectedSessionIndex = (selectedSessionIndex - 1 + availableSessions.size) % availableSessions.size
        openSelectedSession()
        if (replayHandle < 0) {
            stop()
            return
        }
        // Respect user's play/pause state
        autoPlayAfterGuiReady = wasPlaying
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

        val atEnd = tick >= maxTick
        val controls = if (atEnd) "G=Restart  .=Step  [/]=Prev/Next  Esc=Exit  V=Video"
                       else "G=Play/Pause  .=Step  [/]=Prev/Next  Esc=Exit  V=Video"
        ctx.drawText(MinecraftClient.getInstance().textRenderer, controls, 8, 44, 0xAAAAAA, true)
    }

    /**
     * Swallows all outbound messages on the EmbeddedChannel.
     * Matching ReplayMod's DropOutboundMessagesHandler.
     *
     * The EmbeddedChannel's event loop considers every thread to be in it,
     * so multiple threads may try to write simultaneously. Rather than dealing
     * with thread-safety issues, we just drop all writes (there's no real server).
     */
    private class DropOutboundMessagesHandler : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            // Drop all outgoing messages - there is no server
            // Release ref-counted ByteBuf to prevent leaks (matching ReplayMod)
            ReferenceCountUtil.release(msg)
            promise.setSuccess()
        }

        override fun flush(ctx: ChannelHandlerContext) {
            // Drop flush too
        }
    }
}
