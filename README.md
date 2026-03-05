# mine-agent: High-Fidelity Minecraft Gameplay Capture & Replay

A system for recording, replaying, and simulating Minecraft gameplay at tick resolution (20Hz). Modeled after [ReplayMod](https://github.com/ReplayMod/), it captures **all** server-to-client packets at the Netty pipeline level plus synthetic packets for local-player-only events (block breaking, sounds, animations) that the server never sends back to the acting player. Replay uses a fake `ClientConnection` + `EmbeddedChannel` (like ReplayMod's `ReplayHandler`) to dispatch every captured packet through the normal Minecraft networking pipeline, producing a pixel-perfect first-person replay — including inventory, crafting, chests, entities, block changes, cursor movement, arm swing, and all UI screens a real player would see.

## Architecture

```
              Minecraft Client (1.20.1 Fabric)
              ================================
                          |
          +---------------+----------------+
          |                                |
    TickRingBuffer                  RawPacketCapture
 (48-byte tick records)        (Netty pipeline S2C packets)
  - position, rotation          - ALL decoded server packets
  - input flags (WASD, etc)     - millisecond timestamps
  - mouse buttons, cursor       - tick numbers
  - health, food, XP            - packet IDs + raw data
  - velocity, screen type
          |                                |
          |       RecordingEventHandler    |
          |       (synthetic packets)      |
          |        - player position       |
          |        - equipment changes     |
          |        - arm swing animation   |
          |        - block break progress  |
          |        - block break particles |
          |        - block break sounds    |
          |        - entity spawn snapshot |
          |        - inventory slot diffs  |
          |                                |
          +---------------+----------------+
                          |
                    CaptureWriter
                  (background thread)
                          |
                +---------+---------+
                |                   |
          chunks/*.cap         packets.bin
        (LZ4-compressed       (raw packet stream,
         tick records)         v2 format with u32 dataLen)
                |                   |
                +------- + ---------+
                         |
                   capture.sqlite
                  (chunk index DB)
```

### Components

| Component | Language | Description |
|-----------|----------|-------------|
| `mod/` | Kotlin + Java (Fabric) | Minecraft mod: capture, replay, video recording |
| `native/` | Rust (JNI) | Storage engine, chunk I/O, JSON export, simulator |
| `scripts/` | Bash | Automation launcher for recording and export |

## Quick Start

### Prerequisites

- Java 17 (OpenJDK)
- Rust toolchain (stable)
- Minecraft 1.20.1 with Fabric Loader + Fabric API

### Building

```bash
# Build native library (Rust JNI + CLI tools)
cd native
cargo build --release

# Build Minecraft mod
cd mod
./gradlew build
```

The mod JAR will be in `mod/build/libs/`. Copy it to your Minecraft `mods/` folder alongside Fabric API.

### Recording Gameplay

1. Launch Minecraft with the mod installed
2. Join a world or server — recording starts automatically on game join
3. Play normally. Every tick (50ms), two data streams are captured:

**Tick records** (48-byte fixed-size, 20Hz):
- All movement inputs (WASD, jump, sneak, sprint)
- Mouse buttons and cursor screen position
- Player position (x, y, z), rotation (yaw, pitch), velocity
- Health, food, XP level
- Current screen type (inventory, chest, crafting, furnace, etc.)

**Network packets** (variable-size, timestamped):
- **Every S2C packet** the server sends (entity updates, block changes, chat, particles, etc.)
- **Synthetic packets** injected for local-player-only events the server never sends back:
  - `PlayerPositionLookS2CPacket` — player's own position/rotation
  - `EntityEquipmentUpdateS2CPacket` — held item changes
  - `EntityAnimationS2CPacket` — arm swing animation
  - `BlockBreakingProgressS2CPacket` — block crack overlay (from `WorldRendererMixin`)
  - `WorldEventS2CPacket` — block break particles, event ID 2001 (from `WorldMixin`)
  - `PlaySoundS2CPacket` — block break/place sounds (from `WorldMixin`)
  - `EntitySpawnS2CPacket` — initial snapshot of all entities already in the world
  - `InventoryS2CPacket` / `ScreenHandlerSlotUpdateS2CPacket` — inventory item movements

Data is saved to `.minecraft/mcap_replay/sessions/<timestamp>/`.

### Replaying

Replay is launched from the **Replay Center** button on the Minecraft title screen (between Realms and Options). Select a recorded session and click "Load Replay" to start.

**Replay controls** (during replay):

| Key | Action |
|-----|--------|
| `G` | Play/pause (or restart if at end) |
| `.` | Step one tick forward (when paused) |
| `[` / `]` | Previous/next session |
| `Escape` | Exit replay → return to title screen |
| `V` | Toggle video recording (ffmpeg) |

**What happens during replay:**

1. A fake `ClientConnection` + `EmbeddedChannel` is created (matching ReplayMod's `ReplayHandler`)
2. All captured S2C packets are fed through the normal MC packet pipeline via `fireChannelRead()`
3. Tick records overlay position/rotation/hotbar for smooth first-person camera with interpolation
4. Arm swing animations are manually ticked (since `tickMovement()` is cancelled)
5. Client-side inventory (E key) is opened/closed from tick record `screenType` field
6. Server-mediated screens (chests, crafting tables, furnaces) replay via `OpenScreenS2CPacket`
7. Inventory item movements replay via captured slot update packets
8. Cursor position is replayed from tick records via GLFW when inventory screens are open
9. Mouse input is suppressed: cursor movement (prevents camera jitter), scroll (prevents hotbar changes), and clicks on container screens (prevents item movement)
10. Cursor is locked (hidden) when no screen is open, free when inventory/containers are shown
11. Entity spawn snapshot ensures creatures (cows, pigs, villagers, creepers, etc.) present before recording started appear in the replay
12. Block breaking shows full animation: crack overlay, break particles, and break sounds
13. Yaw is unwrapped across the ±327° encoding boundary for smooth continuous rotation

When replay ends, `Escape` returns to the title screen. Pressing `G` at the end restarts the replay from tick 0.

### Exporting to JSON

```bash
# Export a session to JSON Lines format
./native/target/release/export_json path/to/session/ output.jsonl
```

Each line is a JSON object for one tick:
```json
{
  "tick": 42,
  "flags": {"forward": true, "back": false, "left": false, "right": false, "jump": false, "sneak": false, "sprint": true},
  "hotbar": 0,
  "mouse_buttons": {"left": true, "right": false, "middle": false},
  "yaw": 45.0,
  "pitch": -10.0,
  "pos": [100.5, 64.0, 200.3],
  "health": 20.0,
  "food": 18,
  "screen_type": 0,
  "xp_level": 5,
  "velocity": [0.01, -0.08, 0.0],
  "cursor": [512, 384],
  "packets": [{"id": 38, "len": 12, "timestamp_ms": 1050}]
}
```

### Running the Simulator

```bash
# Simulate and verify against recorded positions
./native/target/release/simulator path/to/session/ --verify --output replay.jsonl
```

The simulator replays inputs through a simplified Minecraft physics model and compares simulated positions against recorded positions. This validates that capture data is correct and complete.

### Automation Script

```bash
# Record, export, and optionally upload
./scripts/record_and_export.sh --output-dir /tmp/exports --video --simulate

# With cloud upload
./scripts/record_and_export.sh --upload s3://my-bucket/recordings/ --latest
```

## Storage Format

### Tick Records (48 bytes, little-endian)

| Offset | Type | Field | Description |
|--------|------|-------|-------------|
| 0-1 | u16 | flags | Bit flags: W,S,A,D,jump,sneak,sprint,screen,swing,attack,use,ground,water |
| 2 | u8 | hotbar | Selected hotbar slot (0-8) |
| 3 | u8 | mouseBtn | Bit 0=left, 1=right, 2=middle |
| 4-5 | i16 | yaw_fp | Yaw * 100 (fixed-point) |
| 6-7 | i16 | pitch_fp | Pitch * 100 (fixed-point) |
| 8-11 | u32 | tick | Tick number |
| 12-15 | f32 | x | Player X position |
| 16-19 | f32 | y | Player Y position |
| 20-23 | f32 | z | Player Z position |
| 24-27 | f32 | health | Player health (0-20) |
| 28 | u8 | food | Food level (0-20) |
| 29 | u8 | screenType | 0=none, 1=inventory, 2=chest, 3=crafting, 4=furnace, ... |
| 30-31 | u16 | xpLevel | Experience level |
| 32-35 | f32 | velX | Velocity X |
| 36-39 | f32 | velY | Velocity Y |
| 40-43 | f32 | velZ | Velocity Z |
| 44-45 | i16 | cursorX | Cursor screen X (divided by scaleFactor during capture) |
| 46-47 | i16 | cursorY | Cursor screen Y (divided by scaleFactor during capture) |

### Packet Storage (packets.bin v2)

Per packet: `u32 tick | u32 timestamp_ms | u16 packetId | u32 dataLen | data[dataLen]`

A `packets_v2.marker` file indicates the v2 format. Old format (v1) uses `u16 dataLen` with 8-byte headers.

### Chunk Files (*.cap)

Each `.cap` file contains up to 400 ticks (20 seconds) of tick records:

```
Header (25 bytes):
  "MCAP" magic (4) | schema_version u16 | startTick u32 | tickCount u16 |
  codec u8 (1=LZ4) | uncompressedLen u32 | compressedLen u32 | crc32 u32

Payload:
  LZ4-compressed tick records (48 bytes each)
```

## Key Design Decisions

1. **Netty pipeline capture** (not selective mixins): A `ChannelInboundHandlerAdapter` after the decoder intercepts ALL decoded S2C packets. This matches ReplayMod's approach and ensures no packet types are missed.

2. **Full packet replay via fake connection**: All captured S2C packets are dispatched through a fake `ClientConnection` + `EmbeddedChannel` during replay, matching ReplayMod's `ReplayHandler` architecture. This means inventory screens, chest UIs, entity updates, block changes, and all other server-driven state work automatically during replay. A `ReplayPacketSender` (Netty `ChannelInboundHandlerAdapter`) filters out problematic packet types via a blacklist, and feeds the rest into the pipeline via `fireChannelRead()`. Tick records provide position/rotation/hotbar/cursor as a secondary overlay for smooth camera and UI tracking.

3. **Synthetic packet injection for local-player events**: The Minecraft server never sends the acting player their own position, equipment, animation, block break progress, block break particles, or block break sounds. These are client-side-only events. Mixin hooks in `WorldRendererMixin` (block crack animation), `WorldMixin` (world events + sounds), and `RecordingEventHandler` (position, equipment, arm swing, entity snapshot, inventory diffs) inject synthetic S2C packets into the capture stream so replays are complete.

4. **Three-layer block breaking capture** (matching ReplayMod):
   - `BlockBreakingProgressS2CPacket` — crack overlay animation stages (via `WorldRendererMixin`)
   - `WorldEventS2CPacket` — block break particles, event ID 2001 (via `WorldMixin.syncWorldEvent`)
   - `PlaySoundS2CPacket` — block break/place sounds (via `WorldMixin.playSound`)

5. **Mouse suppression during replay**: `MouseMixin` cancels `onCursorPos` (prevents camera jitter from live mouse), `onMouseScroll` (prevents hotbar changes), and `onMouseButton` on container screens (prevents item movement). Cursor position during inventory screens is replayed from tick records via GLFW.

6. **Smooth interpolation**: Position and rotation use previous-tick tracking for MC's built-in lerp (partial tick fraction). Yaw is unwrapped across the i16 encoding boundary (±327°) to prevent 655° spin artifacts. Cursor position is applied directly from tick records when screens are open.

7. **u32 dataLen**: Packet data lengths use u32 (not u16) to handle large packets like `ChunkDataS2CPacket` which can exceed 64KB.

8. **Tick + millisecond timestamps**: Each packet has both a tick number (for replay synchronization) and a millisecond timestamp (for accurate timing within a tick).

## Comparison with ReplayMod

This system is modeled after [ReplayMod](https://github.com/ReplayMod/) but differs in several ways:

| Feature | ReplayMod | mine-agent |
|---------|-----------|------------|
| POV | Third-person free camera | First-person (player's eyes) |
| Tick records | Not stored (packets only) | 48-byte records at 20Hz with full input state |
| Packet capture | Netty pipeline | Netty pipeline (same approach) |
| Synthetic packets | Position, equipment, sounds, world events | Same + inventory diffs, entity snapshot |
| Replay connection | Fake `ClientConnection` + `EmbeddedChannel` | Same architecture |
| Storage | MCPR (zip of TMCPR + metadata) | SQLite index + LZ4 chunks + raw packet stream |
| Export | Video rendering | JSON Lines + video + tick-by-tick simulator |
| Cursor replay | Not applicable (third-person) | GLFW cursor positioning from tick records |
| Screen replay | Not applicable (third-person) | Client-side inventory + server-mediated containers |
