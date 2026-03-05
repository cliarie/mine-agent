//! Export a captured session to JSON Lines format.
//!
//! Each line is a JSON object representing one tick:
//! ```json
//! {"tick":0,"flags":{"forward":true,"back":false,...},"hotbar":0,"mouse_buttons":{"left":true,...},
//!  "yaw":45.0,"pitch":-10.0,"pos":[100.0,64.0,200.0],"health":20.0,"food":20,
//!  "screen_type":0,"xp_level":5,"velocity":[0.0,-0.08,0.0],"cursor":[512,384],
//!  "packets":[{"id":38,"len":12,"timestamp_ms":1050},...]}
//! ```
//!
//! Usage: export_json <session_dir> [output.jsonl]

use lz4_flex::decompress_size_prepended;
use rusqlite::{params, Connection};
use serde::Serialize;
use std::collections::HashMap;
use std::env;
use std::fs;
use std::io::{self, BufWriter, Write};
use std::path::Path;

#[derive(Serialize)]
struct TickRecord {
    tick: u32,
    flags: FlagSet,
    hotbar: u8,
    mouse_buttons: MouseButtons,
    yaw: f32,
    pitch: f32,
    pos: [f32; 3],
    health: f32,
    food: u8,
    screen_type: u8,
    xp_level: u16,
    velocity: [f32; 3],
    cursor: [i16; 2],
    packets: Vec<PacketRef>,
}

#[derive(Serialize)]
struct FlagSet {
    forward: bool,
    back: bool,
    left: bool,
    right: bool,
    jump: bool,
    sneak: bool,
    sprint: bool,
    screen_open: bool,
    arm_swing: bool,
    attack: bool,
    #[serde(rename = "use")]
    use_key: bool,
    on_ground: bool,
    in_water: bool,
}

#[derive(Serialize)]
struct MouseButtons {
    left: bool,
    right: bool,
    middle: bool,
}

#[derive(Serialize)]
struct PacketRef {
    id: u16,
    len: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    timestamp_ms: Option<u32>,
}

fn main() -> io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: export_json <session_dir> [output.jsonl]");
        std::process::exit(1);
    }

    let session_dir = Path::new(&args[1]);
    let output_path = args.get(2).map(|s| s.as_str());

    let db_path = session_dir.join("capture.sqlite");
    if !db_path.exists() {
        eprintln!("No capture.sqlite found in {}", session_dir.display());
        std::process::exit(1);
    }

    let db = Connection::open(&db_path).unwrap();
    let chunks_dir = session_dir.join("chunks");
    let record_size: usize = 48;

    // Get max tick
    let max_tick: i32 = db
        .query_row("SELECT COALESCE(MAX(endTick), -1) FROM chunks", [], |row| row.get(0))
        .unwrap_or(-1);

    if max_tick < 0 {
        eprintln!("No tick data found");
        std::process::exit(1);
    }

    eprintln!("Exporting {} ticks...", max_tick + 1);

    // Load all chunks into memory indexed by tick range
    let mut stmt = db
        .prepare("SELECT path, startTick, endTick FROM chunks ORDER BY startTick")
        .unwrap();
    let chunks: Vec<(String, i32, i32)> = stmt
        .query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
        .unwrap()
        .filter_map(|r| r.ok())
        .collect();

    // Load packet data
    let packets_path = session_dir.join("packets.bin");
    let packet_data = if packets_path.exists() {
        fs::read(&packets_path).unwrap_or_default()
    } else {
        Vec::new()
    };

    let is_new_format = session_dir.join("packets_v2.marker").exists();
    let header_size: usize = if is_new_format { 14 } else { 8 };

    // Index packets by tick
    let mut packets_by_tick: HashMap<u32, Vec<PacketRef>> = HashMap::new();
    {
        let mut offset = 0;
        while offset + header_size <= packet_data.len() {
            let pkt_tick = u32::from_le_bytes([
                packet_data[offset], packet_data[offset+1],
                packet_data[offset+2], packet_data[offset+3],
            ]);

            let (pkt_id, data_len, timestamp_ms) = if is_new_format {
                let ts = u32::from_le_bytes([
                    packet_data[offset+4], packet_data[offset+5],
                    packet_data[offset+6], packet_data[offset+7],
                ]);
                let id = u16::from_le_bytes([packet_data[offset+8], packet_data[offset+9]]);
                let len = u32::from_le_bytes([packet_data[offset+10], packet_data[offset+11], packet_data[offset+12], packet_data[offset+13]]);
                (id, len as usize, Some(ts))
            } else {
                let id = u16::from_le_bytes([packet_data[offset+4], packet_data[offset+5]]);
                let len = u16::from_le_bytes([packet_data[offset+6], packet_data[offset+7]]);
                (id, len as usize, None)
            };

            offset += header_size;
            if offset + data_len > packet_data.len() { break; }

            packets_by_tick.entry(pkt_tick).or_default().push(PacketRef {
                id: pkt_id,
                len: data_len as u16,
                timestamp_ms,
            });

            offset += data_len;
        }
    }

    // Open output
    let writer: Box<dyn Write> = match output_path {
        Some(p) => Box::new(BufWriter::new(fs::File::create(p)?)),
        None => Box::new(BufWriter::new(io::stdout())),
    };
    let mut writer = writer;

    // Process each chunk
    for (chunk_path, start_tick, end_tick) in &chunks {
        let full_path = chunks_dir.join(chunk_path);
        let data = match fs::read(&full_path) {
            Ok(d) => d,
            Err(_) => continue,
        };
        if data.len() < 25 { continue; }

        let payload = &data[25..];
        let decompressed = match decompress_size_prepended(payload) {
            Ok(d) => d,
            Err(_) => continue,
        };

        for tick in *start_tick..=*end_tick {
            let tick_offset = (tick - start_tick) as usize;
            let byte_offset = tick_offset * record_size;
            if byte_offset + record_size > decompressed.len() { break; }

            let rec = &decompressed[byte_offset..byte_offset + record_size];

            let flags_raw = u16::from_le_bytes([rec[0], rec[1]]);
            let hotbar = rec[2];
            let mouse_btn = rec[3];
            let yaw_fp = i16::from_le_bytes([rec[4], rec[5]]);
            let pitch_fp = i16::from_le_bytes([rec[6], rec[7]]);
            // tick at rec[8..12]
            let x = f32::from_le_bytes([rec[12], rec[13], rec[14], rec[15]]);
            let y = f32::from_le_bytes([rec[16], rec[17], rec[18], rec[19]]);
            let z = f32::from_le_bytes([rec[20], rec[21], rec[22], rec[23]]);
            let health = f32::from_le_bytes([rec[24], rec[25], rec[26], rec[27]]);
            let food = rec[28];
            let screen_type = rec[29];
            let xp_level = u16::from_le_bytes([rec[30], rec[31]]);
            let vel_x = f32::from_le_bytes([rec[32], rec[33], rec[34], rec[35]]);
            let vel_y = f32::from_le_bytes([rec[36], rec[37], rec[38], rec[39]]);
            let vel_z = f32::from_le_bytes([rec[40], rec[41], rec[42], rec[43]]);
            let cursor_x = i16::from_le_bytes([rec[44], rec[45]]);
            let cursor_y = i16::from_le_bytes([rec[46], rec[47]]);

            let record = TickRecord {
                tick: tick as u32,
                flags: FlagSet {
                    forward: flags_raw & 1 != 0,
                    back: flags_raw & 2 != 0,
                    left: flags_raw & 4 != 0,
                    right: flags_raw & 8 != 0,
                    jump: flags_raw & 16 != 0,
                    sneak: flags_raw & 32 != 0,
                    sprint: flags_raw & 64 != 0,
                    screen_open: flags_raw & 128 != 0,
                    arm_swing: flags_raw & 256 != 0,
                    attack: flags_raw & 512 != 0,
                    use_key: flags_raw & 1024 != 0,
                    on_ground: flags_raw & 2048 != 0,
                    in_water: flags_raw & 4096 != 0,
                },
                hotbar,
                mouse_buttons: MouseButtons {
                    left: mouse_btn & 1 != 0,
                    right: mouse_btn & 2 != 0,
                    middle: mouse_btn & 4 != 0,
                },
                yaw: yaw_fp as f32 / 100.0,
                pitch: pitch_fp as f32 / 100.0,
                pos: [x, y, z],
                health,
                food,
                screen_type,
                xp_level,
                velocity: [vel_x, vel_y, vel_z],
                cursor: [cursor_x, cursor_y],
                packets: packets_by_tick.remove(&(tick as u32)).unwrap_or_default(),
            };

            serde_json::to_writer(&mut writer, &record).unwrap();
            writeln!(writer)?;
        }
    }

    writer.flush()?;
    eprintln!("Export complete.");
    Ok(())
}
