//! Tick-by-tick simulator that reads captured session data and replays
//! the player's inputs through a simplified physics model.
//!
//! This produces a deterministic replay of the player's movement given
//! the same inputs, useful for validating that capture data is correct
//! and for training AI agents.
//!
//! Usage: simulator <session_dir> [--verify] [--output replay.jsonl]

use lz4_flex::decompress_size_prepended;
use rusqlite::Connection;
use serde::Serialize;
use std::env;
use std::fs;
use std::io::{self, BufWriter, Write};
use std::path::Path;

const RECORD_SIZE: usize = 48;

// Minecraft movement constants
const GRAVITY: f64 = -0.08;
const DRAG_Y: f64 = 0.98;
const DRAG_XZ: f64 = 0.91;  // on ground
const DRAG_XZ_AIR: f64 = 0.91;
const MOVE_SPEED: f64 = 0.1;
const SPRINT_FACTOR: f64 = 1.3;
const JUMP_VELOCITY: f64 = 0.42;
const SNEAK_FACTOR: f64 = 0.3;

#[derive(Serialize)]
struct SimTick {
    tick: u32,
    input: InputState,
    simulated_pos: [f64; 3],
    recorded_pos: [f32; 3],
    pos_error: f64,
}

#[derive(Serialize)]
struct InputState {
    forward: bool,
    back: bool,
    left: bool,
    right: bool,
    jump: bool,
    sneak: bool,
    sprint: bool,
    yaw: f32,
    pitch: f32,
    attack: bool,
    use_key: bool,
    mouse_left: bool,
    mouse_right: bool,
}

struct SimState {
    x: f64,
    y: f64,
    z: f64,
    vx: f64,
    vy: f64,
    vz: f64,
    on_ground: bool,
}

impl SimState {
    fn step(&mut self, input: &InputState) {
        // Calculate movement direction from inputs
        let yaw_rad = (input.yaw as f64).to_radians();
        let sin_yaw = yaw_rad.sin();
        let cos_yaw = yaw_rad.cos();

        let mut move_x = 0.0f64;
        let mut move_z = 0.0f64;

        if input.forward { move_x -= sin_yaw; move_z += cos_yaw; }
        if input.back { move_x += sin_yaw; move_z -= cos_yaw; }
        if input.left { move_x += cos_yaw; move_z += sin_yaw; }
        if input.right { move_x -= cos_yaw; move_z -= sin_yaw; }

        // Normalize
        let len = (move_x * move_x + move_z * move_z).sqrt();
        if len > 0.001 {
            move_x /= len;
            move_z /= len;
        }

        // Apply speed
        let mut speed = MOVE_SPEED;
        if input.sprint { speed *= SPRINT_FACTOR; }
        if input.sneak { speed *= SNEAK_FACTOR; }

        self.vx += move_x * speed;
        self.vz += move_z * speed;

        // Jump
        if input.jump && self.on_ground {
            self.vy = JUMP_VELOCITY;
            self.on_ground = false;
        }

        // Gravity
        self.vy += GRAVITY;
        self.vy *= DRAG_Y;

        // Horizontal drag
        let drag = if self.on_ground { DRAG_XZ } else { DRAG_XZ_AIR };
        self.vx *= drag;
        self.vz *= drag;

        // Apply velocity
        self.x += self.vx;
        self.y += self.vy;
        self.z += self.vz;

        // Simple ground collision (y=0 plane, very approximate)
        if self.y < 0.0 {
            self.y = 0.0;
            self.vy = 0.0;
            self.on_ground = true;
        }
    }
}

fn main() -> io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: simulator <session_dir> [--verify] [--output replay.jsonl]");
        std::process::exit(1);
    }

    let session_dir = Path::new(&args[1]);
    let verify = args.iter().any(|a| a == "--verify");
    let output_path = args.iter().position(|a| a == "--output").and_then(|i| args.get(i + 1));

    let db_path = session_dir.join("capture.sqlite");
    if !db_path.exists() {
        eprintln!("No capture.sqlite found in {}", session_dir.display());
        std::process::exit(1);
    }

    let db = Connection::open(&db_path).unwrap();
    let chunks_dir = session_dir.join("chunks");

    let max_tick: i32 = db
        .query_row("SELECT COALESCE(MAX(endTick), -1) FROM chunks", [], |row| row.get(0))
        .unwrap_or(-1);

    if max_tick < 0 {
        eprintln!("No tick data found");
        std::process::exit(1);
    }

    eprintln!("Simulating {} ticks...", max_tick + 1);

    // Load chunks
    let mut stmt = db
        .prepare("SELECT path, startTick, endTick FROM chunks ORDER BY startTick")
        .unwrap();
    let chunks: Vec<(String, i32, i32)> = stmt
        .query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
        .unwrap()
        .filter_map(|r| r.ok())
        .collect();

    let writer: Box<dyn Write> = match output_path {
        Some(p) => Box::new(BufWriter::new(fs::File::create(p)?)),
        None => Box::new(BufWriter::new(io::stdout())),
    };
    let mut writer = writer;

    let mut sim = SimState {
        x: 0.0, y: 64.0, z: 0.0,
        vx: 0.0, vy: 0.0, vz: 0.0,
        on_ground: true,
    };

    let mut total_error = 0.0f64;
    let mut tick_count = 0u32;
    let mut initialized = false;

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
            let byte_offset = tick_offset * RECORD_SIZE;
            if byte_offset + RECORD_SIZE > decompressed.len() { break; }

            let rec = &decompressed[byte_offset..byte_offset + RECORD_SIZE];

            let flags_raw = u16::from_le_bytes([rec[0], rec[1]]);
            let mouse_btn = rec[3];
            let yaw_fp = i16::from_le_bytes([rec[4], rec[5]]);
            let pitch_fp = i16::from_le_bytes([rec[6], rec[7]]);
            let x = f32::from_le_bytes([rec[12], rec[13], rec[14], rec[15]]);
            let y = f32::from_le_bytes([rec[16], rec[17], rec[18], rec[19]]);
            let z = f32::from_le_bytes([rec[20], rec[21], rec[22], rec[23]]);

            let input = InputState {
                forward: flags_raw & 1 != 0,
                back: flags_raw & 2 != 0,
                left: flags_raw & 4 != 0,
                right: flags_raw & 8 != 0,
                jump: flags_raw & 16 != 0,
                sneak: flags_raw & 32 != 0,
                sprint: flags_raw & 64 != 0,
                yaw: yaw_fp as f32 / 100.0,
                pitch: pitch_fp as f32 / 100.0,
                attack: flags_raw & 512 != 0,
                use_key: flags_raw & 1024 != 0,
                mouse_left: mouse_btn & 1 != 0,
                mouse_right: mouse_btn & 2 != 0,
            };

            // Initialize sim position from first tick
            if !initialized {
                sim.x = x as f64;
                sim.y = y as f64;
                sim.z = z as f64;
                sim.on_ground = flags_raw & 2048 != 0;
                initialized = true;
            }

            sim.step(&input);

            let pos_error = ((sim.x - x as f64).powi(2)
                + (sim.y - y as f64).powi(2)
                + (sim.z - z as f64).powi(2))
                .sqrt();

            total_error += pos_error;
            tick_count += 1;

            let sim_tick = SimTick {
                tick: tick as u32,
                input,
                simulated_pos: [sim.x, sim.y, sim.z],
                recorded_pos: [x, y, z],
                pos_error,
            };

            serde_json::to_writer(&mut writer, &sim_tick).unwrap();
            writeln!(writer)?;
        }
    }

    writer.flush()?;

    if verify && tick_count > 0 {
        let avg_error = total_error / tick_count as f64;
        eprintln!("Simulation complete: {} ticks, avg position error: {:.4} blocks", tick_count, avg_error);
        if avg_error > 5.0 {
            eprintln!("WARNING: High position error. This is expected since the simulator");
            eprintln!("         uses simplified physics without collision detection.");
        }
    } else {
        eprintln!("Simulation complete: {} ticks processed.", tick_count);
    }

    Ok(())
}
