# native/ - Rust Storage Engine & CLI Tools

Rust library providing JNI bindings for the Minecraft mod's storage layer, plus standalone CLI tools for inspecting, exporting, and simulating captured sessions.

## Building

```bash
cargo build --release
```

Binaries are output to `target/release/`:
- `libmcap_native.so` (or `.dylib` on macOS) - JNI library loaded by the mod
- `inspect_cap` - Inspect `.cap` chunk files
- `export_json` - Export sessions to JSON Lines
- `simulator` - Tick-by-tick physics simulator

## JNI Library (`lib.rs`)

The JNI library provides the following functions to the Kotlin mod:

### Session Management (Recording)

| Function | Description |
|----------|-------------|
| `nativeInitSession(manifestJson, baseDir)` | Create a new capture session directory with SQLite index |
| `nativeAppendTicks(handle, startTick, data, len)` | Append tick records (48 bytes each), auto-flushing to LZ4 chunks |
| `nativeAppendPackets(handle, data, len)` | Append raw packet data to `packets.bin` |
| `nativeCloseSession(handle)` | Flush remaining data and close the session |

### Session Management (Replay)

| Function | Description |
|----------|-------------|
| `nativeOpenReplay(sessionPath)` | Open a session for replay, returns handle |
| `nativeGetReplayMaxTick(handle)` | Get the maximum tick number in the session |
| `nativeReadTick(handle, tick)` | Read a single 48-byte tick record |
| `nativeReadPacketsForTick(handle, tick)` | Read all packets for a tick as `[u16 packetId, u32 dataLen, data[]]...` |
| `nativeCloseReplay(handle)` | Close the replay session |

### Storage Layout

```
mcap_replay/sessions/<timestamp>/
  manifest.json          # Session metadata
  capture.sqlite         # Chunk index (startTick, endTick, path, etc.)
  packets.bin            # Raw S2C packet stream
  packets_v2.marker      # Format version marker (v2 = u32 dataLen)
  chunks/
    000000.cap           # First 400 ticks (20 seconds)
    000001.cap           # Next 400 ticks
    ...
```

### Packet Format Detection

The library auto-detects packet format by checking for `packets_v2.marker`:
- **v2** (current): 14-byte header = `u32 tick + u32 timestamp_ms + u16 packetId + u32 dataLen`
- **v1** (legacy): 8-byte header = `u32 tick + u16 packetId + u16 dataLen`

## CLI Tools

### `inspect_cap` - Chunk Inspector

```bash
# Show chunk header info
./target/release/inspect_cap path/to/000000.cap

# Show tick records
./target/release/inspect_cap path/to/000000.cap --ticks
```

Auto-detects 48-byte (new) vs 28-byte (legacy) record format.

Output columns for 48-byte format:
```
  Idx          Flags  Hot Mse       Yaw     Pitch        Tick         X         Y         Z      HP  Fd Sc
```

Flag characters: `W`=forward, `S`=back, `A`=left, `D`=right, `J`=jump, `C`=sneak, `R`=sprint, `I`=screen, `H`=swing, `K`=attack, `U`=use, `G`=ground, `~`=water

### `export_json` - JSON Lines Exporter

```bash
# Export to stdout
./target/release/export_json path/to/session/

# Export to file
./target/release/export_json path/to/session/ output.jsonl
```

Each line is a complete tick record with all input state, position, health, and packet references:

```json
{"tick":0,"flags":{"forward":true,...},"hotbar":0,"mouse_buttons":{"left":false,...},"yaw":0.0,"pitch":0.0,"pos":[0.0,64.0,0.0],"health":20.0,"food":20,"screen_type":0,"xp_level":0,"velocity":[0.0,0.0,0.0],"cursor":[0,0],"packets":[]}
```

### `simulator` - Physics Simulator

```bash
# Run simulation, output to stdout
./target/release/simulator path/to/session/

# Verify against recorded positions
./target/release/simulator path/to/session/ --verify

# Output to file
./target/release/simulator path/to/session/ --output replay.jsonl
```

The simulator processes each tick's input state through a simplified Minecraft physics model:
- Movement: WASD with yaw-based direction, sprint/sneak speed modifiers
- Gravity: -0.08 blocks/tick, vertical drag 0.98, horizontal drag 0.91
- Jump: 0.42 blocks/tick initial velocity
- Ground collision at y=0 (approximate)

With `--verify`, it reports average position error between simulated and recorded positions. Position errors of 5+ blocks are expected since the simulator doesn't have collision detection or world geometry.

Output format:
```json
{"tick":0,"input":{"forward":true,...},"simulated_pos":[100.0,64.0,200.0],"recorded_pos":[100.0,64.0,200.0],"pos_error":0.0}
```

## Dependencies

- `jni` - JNI bindings for Java/Kotlin interop
- `lz4_flex` - LZ4 compression for chunk files
- `rusqlite` - SQLite for chunk index
- `crc32fast` - CRC32 checksums for chunk integrity
- `serde` / `serde_json` - JSON serialization for export tools
- `chrono` - Timestamp generation for session IDs
- `once_cell` - Lazy static initialization
- `bytemuck` - Safe byte casting for JNI arrays
