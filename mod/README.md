# mod/ - Minecraft Fabric Mod (1.20.1)

Fabric client-side mod that captures and replays Minecraft gameplay at tick resolution (20Hz).

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
| `RecordingEventHandler.kt` | Injects synthetic S2C packets for local player state each tick |
| `CaptureWriter.kt` | Background thread draining tick buffer + packet queue to native storage |

### Replay

| File | Description |
|------|-------------|
| `ReplayController.kt` | Reads tick records + packets, dispatches through `ClientPlayNetworkHandler` |
| `ReplayState.kt` | Global replay state flag (prevents capture during replay) |

### Video

| File | Description |
|------|-------------|
| `VideoRecorder.kt` | OpenGL framebuffer capture piped to ffmpeg for video encoding |

### Mixins

| File | Description |
|------|-------------|
| `ClientPlayNetworkHandlerMixin.java` | Installs `RawPacketCapture` pipeline handler on game join |
| `ClientConnectionAccessor.java` | Accessor mixin exposing Netty `Channel` from `ClientConnection` |
| `EntityPrevAnglesAccessor.java` | Accessor for setting previous yaw/pitch during replay |

### Entry Point

| File | Description |
|------|-------------|
| `McapReplayClient.kt` | Fabric `ClientModInitializer`: registers keybindings, tick handlers, starts capture |

## How Capture Works

1. **Every world tick** (20Hz), `TickRingBuffer.tryWriteFromClient()` samples:
   - Movement keys (WASD, jump, sneak, sprint)
   - Mouse buttons (via GLFW)
   - Player position, rotation, velocity
   - Health, food, XP, screen type, cursor position

2. **At the Netty level**, `RawPacketCapture.PipelinePacketHandler` (installed after the decoder) intercepts every decoded S2C packet, re-serializes it, and queues it with tick number + millisecond timestamp.

3. **Each tick**, `RecordingEventHandler.onPlayerTick()` injects synthetic packets for local player state that the server doesn't send (position, velocity, head yaw, equipment, arm swing).

4. **Background thread** (`CaptureWriter`) drains both the tick ring buffer and packet queue, writing to the native Rust storage engine via JNI.

## How Replay Works

1. `ReplayController.applyRecordedTick()` reads a 48-byte tick record and sets:
   - Player position (with full prev/render position sync)
   - Camera angles (yaw, pitch, head yaw, body yaw)
   - Hotbar slot, arm swing animation

2. `ReplayController.applyPacketsForTick()` reads all raw packets for the current tick and:
   - Constructs `Packet<?>` objects via `NetworkState.PLAY.getPacketHandler()`
   - Filters out problematic packets (disconnect, keep alive, position look, game join)
   - Dispatches remaining packets through `packet.apply(handler)` on the `ClientPlayNetworkHandler`

This means **all UI screens work automatically** -- inventory, crafting tables, chests, furnaces, enchanting tables, anvils, brewing stands, beacons, merchants, hoppers, shulker boxes, and creative inventory all open/close and update correctly during replay, because the same packet handling code processes them as during live play.

## Keybindings

All keybindings are in the "MCAP Replay" category:

| Key | Action | Context |
|-----|--------|---------|
| `R` | Toggle replay mode | Always |
| `G` | Play/pause | During replay |
| `.` | Step one tick forward | During replay (paused) |
| `[` | Previous session | During replay |
| `]` | Next session | During replay |
| `V` | Toggle video recording | During replay |

## HUD

During replay, a HUD overlay shows:
- Play/pause status and tick progress (green)
- Current session name and index (yellow)
- Controls reference (gray)

## Configuration

The mod auto-configures with sensible defaults:
- **Capture buffer**: 2400 ticks (~2 minutes) ring buffer
- **Chunk size**: 400 ticks (20 seconds) per `.cap` file
- **Tick record**: 48 bytes per tick
- **Session directory**: `.minecraft/mcap_replay/sessions/`
