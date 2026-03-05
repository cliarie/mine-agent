# mod/ - Minecraft Fabric Mod (1.20.1)

Fabric client-side mod that captures and replays Minecraft gameplay at tick resolution (20Hz). Produces a first-person replay that looks like a screen recording of the original gameplay session — including all UI screens (inventory, chests, crafting), cursor movement, arm swing animations, block breaking effects, and entity interactions.

## Building

```bash
# Requires Java 17
./gradlew build
```

The mod JAR is output to `build/libs/`. Install it in your Minecraft `mods/` folder alongside Fabric API.

## Module Structure

### Capture

| File | Description |
|------|-------------|
| `TickRingBuffer.kt` | Lock-free ring buffer producing 48-byte tick records at 20Hz |
| `RawPacketCapture.kt` | Netty `ChannelInboundHandlerAdapter` capturing ALL decoded S2C packets |
| `PacketCapture.kt` | Legacy selective mixin-based packet capture (kept for client-side events) |
| `RecordingEventHandler.kt` | Injects synthetic S2C packets for local player state, block breaks, entity snapshots, inventory diffs |
| `CaptureWriter.kt` | Background thread draining tick buffer + packet queue to native storage |

### Replay

| File | Description |
|------|-------------|
| `ReplayHandler.kt` | Full replay lifecycle: fake `ClientConnection` + `EmbeddedChannel` for packet dispatch, tick record overlay for position/rotation/hotbar/cursor, screen open/close, arm swing |
| `ReplayPacketSender.kt` | Netty `ChannelInboundHandlerAdapter` that feeds captured S2C packets into the fake connection pipeline (blacklist filter for problematic types) |
| `ReplayViewerScreen.kt` | Title screen Replay Center: lists recorded sessions, allows load/delete |
| `ReplayController.kt` | Legacy position-only replay (kept for reference, superseded by `ReplayHandler`) |
| `ReplayState.kt` | Global replay state flag (prevents capture during replay) |

### Video

| File | Description |
|------|-------------|
| `VideoRecorder.kt` | OpenGL framebuffer capture piped to ffmpeg for video encoding |

### Mixins

| File | Description |
|------|-------------|
| `ClientPlayNetworkHandlerMixin.java` | Installs `RawPacketCapture` pipeline handler on game join; guards against replay re-capture |
| `ClientConnectionAccessor.java` | Accessor mixin exposing Netty `Channel` from `ClientConnection` |
| `MinecraftClientAccessor.java` | Accessor mixin for swapping `MinecraftClient.integratedServerConnection` (real → fake) |
| `EntityPrevAnglesAccessor.java` | Accessor for setting previous yaw/pitch during replay interpolation |
| `PlayerEntityMixin.java` | Suppresses outgoing movement packets and `tickMovement()` during replay |
| `TitleScreenMixin.java` | Injects "Replay Center" button between Realms and Options on the title screen |
| `MouseMixin.java` | Suppresses mouse cursor/scroll during replay; blocks clicks on container screens |
| `WorldMixin.java` | Captures `syncWorldEvent` (block break particles) and `playSound` (block break sounds) for the local player during recording |
| `WorldRendererMixin.java` | Captures `setBlockBreakingInfo` (block crack animation stages) for the local player during recording |

### Entry Point

| File | Description |
|------|-------------|
| `McapReplayClient.kt` | Fabric `ClientModInitializer`: registers keybindings, tick handlers, starts capture, handles replay controls |

## How Capture Works

1. **Every world tick** (20Hz), `TickRingBuffer.tryWriteFromClient()` samples into a 48-byte record:
   - Movement keys (WASD, jump, sneak, sprint)
   - Mouse buttons (via GLFW) and cursor screen position
   - Player position, rotation, velocity
   - Health, food, XP, screen type (inventory, chest, crafting, etc.)

2. **At the Netty level**, `RawPacketCapture.PipelinePacketHandler` (installed after the decoder by `ClientPlayNetworkHandlerMixin`) intercepts every decoded S2C packet, re-serializes it, and queues it with tick number + millisecond timestamp.

3. **Each tick**, `RecordingEventHandler.onPlayerTick()` injects synthetic packets for local player state that the server doesn't send:
   - `PlayerPositionLookS2CPacket` — player's own position/rotation
   - `EntityVelocityUpdateS2CPacket` — player's own velocity
   - `EntitySetHeadYawS2CPacket` — head rotation
   - `EntityEquipmentUpdateS2CPacket` — held item / armor changes
   - `EntityAnimationS2CPacket` — arm swing animation

4. **On recording start**, `RecordingEventHandler` injects a snapshot of the world:
   - `EntitySpawnS2CPacket` + `EntityTrackerUpdateS2CPacket` for all entities already loaded (cows, pigs, villagers, creepers, etc.)
   - `InventoryS2CPacket` for the full player inventory state
   - Per-tick `ScreenHandlerSlotUpdateS2CPacket` for any inventory slot changes

5. **Block breaking capture** (three-layer, matching ReplayMod):
   - `WorldRendererMixin` hooks `setBlockBreakingInfo()` → injects `BlockBreakingProgressS2CPacket` (crack overlay stages)
   - `WorldMixin` hooks `syncWorldEvent()` → injects `WorldEventS2CPacket` (block break particles, event ID 2001)
   - `WorldMixin` hooks `playSound()` → injects `PlaySoundS2CPacket` (block break/place sounds)

   The server never sends these packets to the player doing the breaking — only to other players. Without these hooks, block breaks would be silent and particle-less in replay.

6. **Background thread** (`CaptureWriter`) drains both the tick ring buffer and packet queue, writing to the native Rust storage engine via JNI.

## How Replay Works

Replay uses a fake `ClientConnection` + `EmbeddedChannel` architecture (like ReplayMod's `ReplayHandler`):

1. **Replay Center** (title screen button → `ReplayViewerScreen`) lists all recorded sessions with date, duration, chunk count, and tick count. Select a session and click "Load Replay" to start.

2. **`ReplayHandler.startSession(sessionDir)`** opens the session via native bridge and calls `setupFakeConnection()`.

3. **`setupFakeConnection()`** creates a fake `ClientConnection(NetworkSide.CLIENTBOUND)`, wraps it in an `EmbeddedChannel` with a 6-stage Netty pipeline matching ReplayMod:
   - `DropOutbound` — swallows all outgoing writes (no real server)
   - `DecoderHandler` — decodes raw bytes into MC packets
   - `PacketEncoder` — encodes (unused, required for pipeline symmetry)
   - `PacketBundler` — handles packet bundles
   - `ReplayPacketSender` — filters and dispatches S2C packets
   - `ClientConnection` — normal MC connection handler

4. **`MinecraftClientAccessor`** swaps `MinecraftClient.integratedServerConnection` to point at the fake connection.

5. **Each tick**, `ReplayPacketSender.sendPacketsForTick(tick)` reads captured packets from the native bridge, wraps them in `ByteBuf`, and fires them into the channel via `fireChannelRead()`.

6. **Tick record overlay** (`applyTickRecord()`) provides smooth first-person camera:
   - Position with previous-tick tracking for MC's built-in lerp interpolation
   - Rotation with yaw unwrapping across the ±327° i16 encoding boundary
   - Hotbar slot selection
   - Arm swing animation (manually ticked since `tickMovement()` is cancelled)
   - Screen open/close from `screenType` field (client-side inventory via E key)
   - Cursor position replay via GLFW when inventory screens are open

7. **Mouse suppression** (`MouseMixin`):
   - `onCursorPos` cancelled — prevents live mouse from changing camera yaw/pitch
   - `onMouseScroll` cancelled — prevents scroll wheel hotbar changes
   - `onMouseButton` cancelled on `HandledScreen` — prevents item movement in containers
   - Cursor locked (hidden) when no screen is open; free when screens are shown

8. **GUI readiness**: Waits for terrain to visually render before auto-playing (closes `DownloadingTerrainScreen`, waits 10+ ticks).

When replay ends, `Escape` returns to the title screen. `G` at the end restarts from tick 0.

## Keybindings

All keybindings are in the "MCAP Replay" category:

| Key | Action | Context |
|-----|--------|---------|
| `G` | Play/pause (or restart at end) | During replay |
| `.` | Step one tick forward | During replay (paused) |
| `[` | Previous session | During replay |
| `]` | Next session | During replay |
| `Escape` | Exit replay (return to title screen) | During replay (no screen open) |
| `V` | Toggle video recording | During replay |

Note: `Escape` distinguishes between "close a screen" (inventory, chest) and "exit replay". If a screen was open, `Escape` closes it first. Only when no screen is open does `Escape` exit replay.

## HUD

During replay, a HUD overlay shows:
- Play/pause status and tick progress (green)
- Current session name and index (yellow)
- World loading status (gray)
- Controls reference including context-aware `G=Restart` when at end (gray)

## Configuration

The mod auto-configures with sensible defaults:
- **Capture buffer**: 2400 ticks (~2 minutes) ring buffer
- **Chunk size**: 400 ticks (20 seconds) per `.cap` file
- **Tick record**: 48 bytes per tick
- **Session directory**: `.minecraft/mcap_replay/sessions/`
