# mine-agent: High-Fidelity Minecraft Gameplay Capture & Replay

A system for recording, replaying, and simulating Minecraft gameplay at tick resolution (20Hz). Inspired by [ReplayMod](https://github.com/ReplayMod/), it captures **all** server-to-client packets at the Netty pipeline level for storage and analysis. Live in-game replay currently shows position, rotation, hotbar, and arm swing from tick records. Full packet-based UI replay (inventory, crafting, chests, etc.) is planned as future work requiring a fake server connection.

## Architecture

```
                    Minecraft Client (1.20.1 Fabric)
                    ================================
                              |
              +---------------+----------------+
              |                                |
        TickRingBuffer                  RawPacketCapture
    (48-byte tick records)        (Netty pipeline S2C packets)
     - position, rotation          - ALL decoded packets
     - input flags (WASD, etc)     - millisecond timestamps
     - mouse buttons               - tick numbers
     - health, food, XP            - packet IDs + data
     - velocity, cursor
     - screen type
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
- Minecraft 1.20.1 with Fabric Loader

### Building

```bash
# Build native library (Rust JNI + CLI tools)
cd native
cargo build --release

# Build Minecraft mod
cd mod
./gradlew build
```

The mod JAR will be in `mod/build/libs/`. Copy it to your Minecraft `mods/` folder.

### Recording Gameplay

1. Launch Minecraft with the mod installed
2. Join a world or server -- recording starts automatically
3. Play normally. Every tick (50ms) is captured:
   - All movement inputs (WASD, jump, sneak, sprint)
   - Mouse buttons and cursor position
   - Player position, rotation, velocity
   - Health, food, XP level
   - Current screen type (inventory, chest, crafting, etc.)
   - **Every S2C packet** the server sends (entity updates, block changes, chat, particles, etc.)

Data is saved to `.minecraft/mcap_replay/sessions/<timestamp>/`.

### Replaying

While in-game, press:

| Key | Action |
|-----|--------|
| `R` | Start/stop replay mode |
| `G` | Play/pause |
| `.` | Step one tick |
| `[` / `]` | Previous/next session |
| `V` | Toggle video recording |

During replay, the mod:
1. Sets player position/rotation from tick records
2. Applies hotbar slot changes and arm swing animations
3. Camera follows the recorded first-person perspective

**Note:** Live replay currently shows position/rotation/hotbar only. Raw S2C packet replay is captured and stored but not dispatched during live replay (requires a fake server connection to avoid corrupting the active game session). Packet data is available via `export_json` and the simulator.

### Exporting to JSON

```bash
# Export a session to JSON Lines format
./native/target/release/export_json path/to/session/ output.jsonl
```

Each line is a JSON object for one tick:
```json
{
  "tick": 42,
  "flags": {"forward": true, "back": false, "left": false, "right": false, "jump": false, "sneak": false, "sprint": true, ...},
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
| 44-45 | i16 | cursorX | Cursor screen X (scaled) |
| 46-47 | i16 | cursorY | Cursor screen Y (scaled) |

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

2. **Packet capture for analysis**: All S2C packets are captured and stored. Live in-game replay currently uses tick records only (position, rotation, hotbar). Full packet dispatch replay requires a fake server connection to avoid corrupting the active game session (similar to ReplayMod's `ReplayHandler` architecture). Packet data is accessible via `export_json` and the tick-by-tick simulator.

3. **Synthetic packet injection**: The server never sends the local player their own position/equipment/animation packets. `RecordingEventHandler` injects these as synthetic S2C packets so they appear in the capture stream.

4. **u32 dataLen**: Packet data lengths use u32 (not u16) to handle large packets like `ChunkDataS2CPacket` which can exceed 64KB.

5. **Tick + millisecond timestamps**: Each packet has both a tick number (for replay synchronization) and a millisecond timestamp (for accurate timing within a tick).
