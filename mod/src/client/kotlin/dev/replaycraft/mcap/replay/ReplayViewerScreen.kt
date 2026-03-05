package dev.replaycraft.mcap.replay

import dev.replaycraft.mcap.native.NativeBridge
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Replay Center screen accessible from the title screen.
 * Lists all recorded sessions and allows the user to load one for replay.
 * Modeled after ReplayMod's GuiReplayViewer.
 */
class ReplayViewerScreen(private val parent: Screen) : Screen(Text.literal("Replay Center")) {

    private var sessionList: SessionListWidget? = null
    private var loadButton: ButtonWidget? = null
    private var deleteButton: ButtonWidget? = null

    override fun init() {
        // Session list widget (centered, with margins for title and buttons)
        sessionList = SessionListWidget(client!!, width, height - 96, 32, 36)
        addDrawableChild(sessionList!!)

        // Load button
        loadButton = ButtonWidget.builder(Text.literal("Load Replay")) { onLoad() }
            .dimensions(width / 2 - 154, height - 52, 150, 20)
            .build()
        loadButton!!.active = false
        addDrawableChild(loadButton!!)

        // Delete button
        deleteButton = ButtonWidget.builder(Text.literal("Delete")) { onDelete() }
            .dimensions(width / 2 + 4, height - 52, 72, 20)
            .build()
        deleteButton!!.active = false
        addDrawableChild(deleteButton!!)

        // Cancel button
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Cancel")) { close() }
                .dimensions(width / 2 + 82, height - 52, 72, 20)
                .build()
        )

        // Load sessions
        loadSessions()
    }

    private fun loadSessions() {
        val mc = client ?: return
        val sessionsDir = File(mc.runDirectory, "mcap_replay/sessions")

        if (!sessionsDir.exists()) return

        val sessions = sessionsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?: return

        for (session in sessions) {
            val chunksDir = File(session, "chunks")
            val chunkCount = chunksDir.listFiles()?.count { it.extension == "cap" } ?: 0
            if (chunkCount < 1) continue

            // Try to get max tick count for duration
            var maxTick = 0
            try {
                val handle = NativeBridge.nativeOpenReplay(session.absolutePath)
                if (handle >= 0) {
                    maxTick = NativeBridge.nativeGetReplayMaxTick(handle)
                    NativeBridge.nativeCloseReplay(handle)
                }
            } catch (_: Exception) {}

            sessionList?.addEntry(SessionEntry(session, chunkCount, maxTick))
        }
    }

    private fun onLoad() {
        val entry = sessionList?.selectedOrNull ?: return

        // Create a ReplayHandler and start replay for the selected session
        val handler = ReplayHandler()
        handler.startSession(entry.sessionDir)

        // The ReplayHandler manages its own tick loop via McapReplayClient
        // Store it as the active replay handler
        McapReplayClientBridge.setActiveReplay(handler)
    }

    private fun onDelete() {
        val entry = sessionList?.selectedOrNull ?: return
        val mc = client ?: return

        // Show confirmation - pass TitleScreen (parent) so after delete we get a fresh viewer
        mc.setScreen(ConfirmDeleteScreen(parent, entry.sessionDir))
    }

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(ctx, mouseX, mouseY, delta)
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF)

        if (sessionList?.children()?.isEmpty() == true) {
            ctx.drawCenteredTextWithShadow(
                textRenderer,
                "No recorded sessions found",
                width / 2,
                height / 2 - 10,
                0x808080
            )
            ctx.drawCenteredTextWithShadow(
                textRenderer,
                "Join a world to start recording automatically",
                width / 2,
                height / 2 + 5,
                0x606060
            )
        }
    }

    private fun updateButtonState() {
        val hasSelection = sessionList?.selectedOrNull != null
        loadButton?.active = hasSelection
        deleteButton?.active = hasSelection
    }

    // ---- Inner classes ----

    inner class SessionListWidget(
        mc: MinecraftClient,
        width: Int,
        height: Int,
        top: Int,
        itemHeight: Int
    ) : AlwaysSelectedEntryListWidget<SessionEntry>(mc, width, height, top, height - 64, itemHeight) {

        public override fun addEntry(entry: SessionEntry): Int {
            return super.addEntry(entry)
        }

        override fun setSelected(entry: SessionEntry?) {
            super.setSelected(entry)
            updateButtonState()
        }
    }

    inner class SessionEntry(
        val sessionDir: File,
        private val chunkCount: Int,
        private val maxTick: Int
    ) : AlwaysSelectedEntryListWidget.Entry<SessionEntry>() {

        private val sessionName: String = sessionDir.name
        private val durationStr: String = formatDuration(maxTick)
        private val dateStr: String = formatSessionDate(sessionDir.name)

        override fun render(
            ctx: DrawContext,
            index: Int,
            y: Int,
            x: Int,
            entryWidth: Int,
            entryHeight: Int,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            tickDelta: Float
        ) {
            val mc = MinecraftClient.getInstance()
            val tr = mc.textRenderer

            // Session name (date)
            ctx.drawText(tr, dateStr, x + 3, y + 2, 0xFFFFFF, true)

            // Duration and chunk info
            val info = "$durationStr  |  $chunkCount chunks  |  $maxTick ticks"
            ctx.drawText(tr, info, x + 3, y + 14, 0xAAAAAA, true)
        }

        override fun getNarration(): Text = Text.literal(sessionName)

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            sessionList?.setSelected(this)
            return true
        }

        private fun formatDuration(ticks: Int): String {
            val totalSeconds = ticks / 20
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "${minutes}m ${seconds}s"
        }

        private fun formatSessionDate(name: String): String {
            return try {
                // Session names are typically timestamps like "20260304_231500"
                val parts = name.split("_")
                if (parts.size >= 2) {
                    val datePart = parts[0]
                    val timePart = parts[1]
                    val year = datePart.substring(0, 4)
                    val month = datePart.substring(4, 6)
                    val day = datePart.substring(6, 8)
                    val hour = timePart.substring(0, 2)
                    val min = timePart.substring(2, 4)
                    val sec = timePart.substring(4, 6)
                    "$year-$month-$day $hour:$min:$sec"
                } else {
                    name
                }
            } catch (_: Exception) {
                name
            }
        }
    }
}

/**
 * Simple confirmation screen for deleting a session.
 */
class ConfirmDeleteScreen(
    private val titleScreen: Screen,
    private val sessionDir: File
) : Screen(Text.literal("Delete Replay?")) {

    override fun init() {
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Delete")) {
                // Delete the session directory recursively
                sessionDir.deleteRecursively()
                // Navigate to a fresh ReplayViewerScreen with the TitleScreen as parent
                client?.setScreen(ReplayViewerScreen(titleScreen))
            }.dimensions(width / 2 - 104, height / 2 + 10, 100, 20).build()
        )

        addDrawableChild(
            ButtonWidget.builder(Text.literal("Cancel")) {
                // Go back to a fresh ReplayViewerScreen (session list)
                client?.setScreen(ReplayViewerScreen(titleScreen))
            }.dimensions(width / 2 + 4, height / 2 + 10, 100, 20).build()
        )
    }

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(ctx, mouseX, mouseY, delta)
        ctx.drawCenteredTextWithShadow(
            textRenderer,
            "Are you sure you want to delete this replay?",
            width / 2,
            height / 2 - 20,
            0xFFFFFF
        )
        ctx.drawCenteredTextWithShadow(
            textRenderer,
            sessionDir.name,
            width / 2,
            height / 2 - 6,
            0xAAAAAA
        )
    }
}

/**
 * Bridge to set the active replay handler from the screen.
 * This is set from the ReplayViewerScreen and read by McapReplayClient.
 */
object McapReplayClientBridge {
    private var activeReplay: ReplayHandler? = null

    fun setActiveReplay(handler: ReplayHandler) {
        activeReplay = handler
    }

    fun getActiveReplay(): ReplayHandler? = activeReplay

    fun clearActiveReplay() {
        activeReplay = null
    }
}
